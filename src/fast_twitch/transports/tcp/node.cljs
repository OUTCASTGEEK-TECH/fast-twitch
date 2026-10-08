(ns fast-twitch.transports.tcp.node
  (:require [cljs.core :refer [await]]
            [fast-twitch.util.tcp :as data]
            [fast-twitch.util.async.cancellation :as cancellation])
  (:refer-global :only [globalThis Promise ReadableStream]))

(defn builtin
  [name]
  (.getBuiltinModule (aget globalThis "process") name))

(defn wrap
  [socket options]
  (let [stream (builtin "node:stream")
        ended? (atom false)
        readable
          (ReadableStream.
            #js {:start
                   (fn [controller]
                     (.pause socket)
                     (.on socket
                          "data"
                          (fn [bytes]
                            (when-not @ended?
                              (.enqueue controller bytes)
                              (when (<= (.-desiredSize controller) 0) (.pause socket)))))
                     (.once socket
                            "end"
                            (fn []
                              (when (compare-and-set! ended? false true)
                                (.close controller))))
                     (.once socket
                            "error"
                            (fn [error]
                              (when (compare-and-set! ended? false true)
                                (.error controller error))))
                     (.once socket
                            "close"
                            (fn []
                              (when (compare-and-set! ended? false true)
                                (.error controller
                                        (ex-info "TCP closed before peer EOF"
                                                 {:code :fast-twitch.tcp/closed}))))))
                 :pull #(.resume socket)
                 :cancel (fn [_]
                           (reset! ended? true)
                           (.destroy socket))}
            #js {:highWaterMark 65536 :size #(.-byteLength %)})]
    ;; Readable.toWeb waits for both sides of a duplex to finish. Peer FIN
    ;; must end only this readable so the application can still send a reply.
    (data/connection-map socket
                         readable
                         (.toWeb (aget stream "Writable") socket)
                         {:close! #(.destroy socket)
                          :half-close! #(.end socket)
                          :local-address #(.-localAddress socket)
                          :remote-address #(.-remoteAddress socket)}
                         options)))

(defn ^:async connect!
  [{:keys! [hostname port] :keys [tls? signal] :as options}]
  (cancellation/aborted! signal)
  (let [module (builtin (if tls? "node:tls" "node:net"))
        socket (.connect module #js {:host hostname :port port :allowHalfOpen true})
        connection (wrap socket options)
        ready (Promise.withResolvers)
        event (if tls? "secureConnect" "connect")
        opened? (atom false)
        opened (fn []
                 (reset! opened? true)
                 (.resolve ready nil))
        closed (fn []
                 (.reject ready
                          (ex-info "Closed before connect"
                                   {:code :fast-twitch.tcp/closed-before-connect})))
        abort (fn []
                (when-not @opened?
                  (.reject ready (.-reason signal))
                  (.destroy socket)))]
    (try
      (.once socket event opened)
      (.once socket "error" (.-reject ready))
      (.once socket "close" closed)
      (when signal
        (.addEventListener signal "abort" abort #js {:once true})
        (when (.-aborted signal) (abort)))
      (await (.-promise ready))
      connection
      (catch :default error (.destroy socket) (throw error))
      (finally
        (.removeListener socket event opened)
        (.removeListener socket "error" (.-reject ready))
        (.removeListener socket "close" closed)
        (when signal (.removeEventListener signal "abort" abort))))))

(defn ^:async listen!
  [{:keys [hostname port] :or {hostname "127.0.0.1" port 0} :as options} on-connection]
  (let [server (.createServer (builtin "node:net")
                              #js {:allowHalfOpen true}
                              #(on-connection (wrap % options)))
        ready (Promise.withResolvers)]
    (.once server "error" (.-reject ready))
    (.listen server #js {:host hostname :port port} #(.resolve ready nil))
    (await (.-promise ready))
    (let [promisify (aget (builtin "node:util") "promisify")
          close (promisify (.-close server))]
      {:fast-twitch.tcp/listener server
       :port (aget (.address server) "port")
       :close! (^:async fn
                []
                (await (.call close server))
                nil)})))
