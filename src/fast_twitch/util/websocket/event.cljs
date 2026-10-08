(ns fast-twitch.util.websocket.event
  (:require [fast-twitch.util.websocket.message :as message]))

(defn event->map
  [type connection event data]
  (cond-> {:type type :connection connection}
    event (assoc :fast-twitch.websocket/event event)
    (= type :message) (assoc :message (message/message->map data))
    (= type :close) (merge (if event
                             {:code (.-code event)
                              :reason (.-reason event)
                              :was-clean? (.-wasClean event)}
                             data))
    (= type :error) (assoc :error (or data event))))
