(ns fast-twitch.transports.tcp.bun
  (:require [cljs.core :refer [await]]
            [fast-twitch.util.async.cancellation :as cancellation]
            [fast-twitch.util.tcp :as data]
            [fast-twitch.util.binary :as binary])
  (:refer-global :only [globalThis ReadableStream WritableStream Promise WeakMap]))

(defn adapter
  [socket options]
  (let [read-controller (atom nil)
        pending (atom nil)
        ended? (atom false)
        fail-write (fn [error]
                     (when-let [p @pending]
                       (reset! pending nil)
                       (.reject (:completion p) error)))
        drain
          (fn drain []
            (when-let [{:keys! [bytes offset completion]} @pending]
              (try
                (let [remaining (- (.-byteLength bytes) offset)
                      n (if (zero? remaining) 0 (.write socket (.subarray bytes offset)))
                      next (+ offset n)]
                  (when (or (not (integer? n)) (neg? n) (> n remaining))
                    (throw (ex-info "Native TCP write failed"
                                    {:code :fast-twitch.tcp/write-failed :written n})))
                  (if (= next (.-byteLength bytes))
                    (do (reset! pending nil) (.resolve completion nil))
                    (swap! pending assoc :offset next)))
                (catch :default error (reset! pending nil) (.reject completion error)))))
        readable (ReadableStream. #js {:start #(reset! read-controller %)
                                       :pull #(.resume socket)
                                       :cancel (fn [_]
                                                 (reset! ended? true)
                                                 (.terminate socket))}
                                  #js {:highWaterMark 65536 :size #(.-byteLength %)})
        writable (WritableStream.
                   #js {:write (^:async fn
                                [value]
                                (let [bytes (binary/bytes value)
                                      completion (Promise.withResolvers)]
                                  (reset! pending
                                    {:bytes bytes :offset 0 :completion completion})
                                  (drain)
                                  (await (.-promise completion))))
                        :close #(.shutdown socket)
                        :abort (fn [_]
                                 (.terminate socket))})]
    ;; Bun 1.4.2 shutdown(true) shuts down reading. The no-argument native
    ;; operation sends write FIN; verified against its source and live sockets.
    {:connection (data/connection-map socket
                                      readable
                                      writable
                                      {:close! #(.terminate socket)
                                       :half-close! #(.shutdown socket)}
                                      options)
     :data (fn [bytes]
             (when-not @ended?
               (.enqueue @read-controller bytes)
               (when (<= (.-desiredSize @read-controller) 0) (.pause socket))))
     :drain drain
     :end (fn []
            (when (compare-and-set! ended? false true) (.close @read-controller)))
     :error (fn [error]
              (fail-write error)
              (when (compare-and-set! ended? false true) (.error @read-controller error)))
     :close (fn []
              (fail-write (ex-info "TCP closed during write"
                                   {:code :fast-twitch.tcp/closed}))
              (when (compare-and-set! ended? false true) (.close @read-controller)))}))

(defn handlers
  [adapters on-open options]
  #js {:open (fn [socket]
               (let [a (adapter socket options)]
                 (.set adapters socket a)
                 (on-open (:connection a))))
       :data (fn [socket bytes]
               ((:data (.get adapters socket)) bytes))
       :drain (fn [socket]
                ((:drain (.get adapters socket))))
       :end (fn [socket]
              ((:end (.get adapters socket))))
       :error (fn [socket error]
                (when-let [a (.get adapters socket)] ((:error a) error)))
       :close (fn [socket _]
                (when-let [a (.get adapters socket)] ((:close a)))
                (.delete adapters socket))})

(defn ^:async connect!
  [{:keys! [hostname port] :keys [tls? signal] :as options}]
  (cancellation/aborted! signal)
  (let [adapters (WeakMap.)
        socket (await (cancellation/await-owned! (.connect (aget globalThis "Bun")
                                                           #js {:hostname hostname
                                                                :port port
                                                                :tls (boolean tls?)
                                                                :allowHalfOpen true
                                                                :socket (handlers
                                                                          adapters
                                                                          (fn [_]
                                                                            nil)
                                                                          options)})
                                                 signal
                                                 #(.terminate %)))]
    (req! (.get adapters socket) :connection)))

(defn listen!
  [{:keys [hostname port] :or {hostname "127.0.0.1" port 0} :as options} on-connection]
  (let [server (.listen (aget globalThis "Bun")
                        #js {:hostname hostname
                             :port port
                             :allowHalfOpen true
                             :socket (handlers (WeakMap.) on-connection options)})]
    {:fast-twitch.tcp/listener server
     :port (.-port server)
     :close! #(.stop server true)}))
