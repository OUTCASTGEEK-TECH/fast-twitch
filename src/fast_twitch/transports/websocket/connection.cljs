(ns fast-twitch.transports.websocket.connection
  (:require [fast-twitch.transports.websocket.protocols :as p]
            [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.websocket.message :as message]))

(defrecord Connection [socket operations]
  p/Socket
    (-open? [_]
      ((:open? operations) socket))
    (-send [_ data]
      (when-not ((:open? operations) socket)
        (v/fail! :fast-twitch.websocket/not-open :send [] :open))
      ((v/capability! operations :send) socket (message/map->message data)))
    (-ping [_ data]
      ((v/capability! operations :ping) socket (message/map->message data)))
    (-pong [_ data]
      ((v/capability! operations :pong) socket (message/map->message data)))
    (-close [_ code reason]
      ((v/capability! operations :close) socket code reason))
  p/AsyncSocket
    (-send-async [this data succeed fail]
      (let [callback (try (p/-send this data)
                          succeed
                          (catch :default error #(fail error)))]
        (try (callback) (catch :default _ nil))
        nil)))

(defn connection
  [socket operations]
  (assoc (->Connection socket operations) :fast-twitch.websocket/socket socket))

(defn native
  [socket]
  (connection socket
              {:open? #(= 1 (.-readyState %))
               :send (fn [s data]
                       (.send s data))
               :close (fn [s code reason]
                        (.close s code reason))}))

(def ^:private CloseOptions
  (m/schema [:map {:closed true} [:code {:optional true} integer?]
             [:reason {:optional true} :string]]))

(mx/defn ^:dynamic close!
  ([connection]
   (close! connection {}))
  ([connection options :- CloseOptions]
   (let [{:keys [code reason] :or {code 1000 reason ""}} options]
     (p/-close connection code reason))))

(set! close!
      (contracts/instrument 'fast-twitch.transports.websocket.connection/close! close!))
