(ns fast-twitch.transports.tcp.deno
  (:require [cljs.core :refer [await]]
            [fast-twitch.util.tcp :as data]
            [fast-twitch.util.async.cancellation :as cancellation]
            [fast-twitch.util.binary :as binary])
  (:refer-global :only [globalThis ReadableStream WritableStream Uint8Array]))

(defn wrap
  [socket options]
  (let [closed? (atom false)
        ended? (atom false)
        write-close (atom nil)
        close! (fn []
                 (when (compare-and-set! closed? false true) (.close socket)))
        half-close! (fn []
                      (or @write-close
                          (let [pending ((^:async fn
                                          []
                                          (await (.closeWrite socket))))]
                            (reset! write-close pending)
                            pending)))
        readable (ReadableStream.
                   #js {:pull (^:async fn
                               [controller]
                               (when-not @ended?
                                 (try (let [buffer (Uint8Array. 65536)
                                            n (await (.read socket buffer))]
                                        (when-not @ended?
                                          (if (nil? n)
                                            (do (reset! ended? true) (.close controller))
                                            (.enqueue controller
                                                      (.subarray buffer 0 n)))))
                                      (catch :default error
                                        (when (compare-and-set! ended? false true)
                                          (.error controller error))))))
                        :cancel (fn [_]
                                  (reset! ended? true)
                                  (close!))}
                   #js {:highWaterMark 65536 :size #(.-byteLength %)})
        writable
          (WritableStream.
            #js {:write
                   (^:async fn
                    [value]
                    (let [bytes (binary/bytes value)
                          length (.-byteLength bytes)]
                      (loop [offset 0]
                        (when (< offset length)
                          (let [n (await (.write socket (.subarray bytes offset)))]
                            (when (or (not (integer? n)) (<= n 0) (> n (- length offset)))
                              (throw (ex-info "Native TCP write failed"
                                              {:code :fast-twitch.tcp/write-failed
                                               :written n})))
                            (recur (+ offset n)))))))
                 :close half-close!
                 :abort (fn [_]
                          (close!))})]
    ;; Conn.readable closes the connection resource on EOF. Native read/write
    ;; operations preserve the write side after peer FIN.
    (data/connection-map socket
                         readable
                         writable
                         {:close! close!
                          :half-close! half-close!
                          :local-address (.-localAddr socket)
                          :remote-address (.-remoteAddr socket)}
                         options)))

(defn ^:async connect!
  [{:keys! [hostname port] :keys [tls? signal] :as options}]
  (cancellation/aborted! signal)
  (let [deno (aget globalThis "Deno")
        ;; Deno 2.9.5 plain TCP accepts AbortSignal. TLS options do not.
        pending (if tls?
                  (.connectTls deno #js {:hostname hostname :port port})
                  (.connect deno #js {:hostname hostname :port port :signal signal}))
        socket (await (cancellation/await-owned! pending signal #(.close %)))]
    (wrap socket options)))

(defn listen!
  [{:keys [hostname port] :or {hostname "127.0.0.1" port 0} :as options} on-connection]
  (let [listener (.listen (aget globalThis "Deno") #js {:hostname hostname :port port})
        stopped? (atom false)]
    ((^:async fn
      []
      (try (loop []
             (when-not @stopped?
               (let [socket (await (.accept listener))]
                 (on-connection (wrap socket options))
                 (recur))))
           (catch :default error (when-not @stopped? (throw error))))))
    {:fast-twitch.tcp/listener listener
     :port (aget (.-addr listener) "port")
     :close! #(do (reset! stopped? true) (.close listener))}))
