(ns fast-twitch.transports.websocket.listener
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.transports.websocket.protocols :as p]
            [fast-twitch.transports.websocket.connection :as c]
            [fast-twitch.util.websocket.event :as event]))

(def Listener
  (m/schema
    [:or [:fn #(satisfies? p/Listener %)]
     [:map-of [:enum :on-open :on-message :on-ping :on-pong :on-error :on-close]
      [:maybe contracts/Function]]]))

(defn ^:async invoke!
  [listener type connection data]
  (try (let [mapped? (map? listener)
             call (if mapped?
                    (get listener type)
                    (case type
                      :on-open p/on-open
                      :on-message p/on-message
                      :on-error p/on-error
                      :on-close p/on-close
                      :on-pong p/on-pong
                      :on-ping (when (satisfies? p/PingListener listener) p/on-ping)))]
         (when call
           (await (if mapped?
                    (apply call connection data)
                    (apply call listener connection data)))))
       (catch :default error
         (when-not (= type :on-error)
           (await (invoke! listener :on-error connection [error]))))))

(defn attach!
  [socket listener {:keys [on-event on-dispose]}]
  (let [connection (c/native socket)
        opened? (atom false)
        subscriptions (atom [])
        cleanup (fn []
                  (doseq [[type f] @subscriptions] (.removeEventListener socket type f))
                  (reset! subscriptions [])
                  (when on-dispose (on-dispose connection)))
        emit (fn [type e data]
               (when on-event (on-event (event/event->map type connection e data))))
        handlers
          {"open" (fn [e]
                    (reset! opened? true)
                    (invoke! listener :on-open connection [])
                    (emit :open e nil))
           "message" (fn [e]
                       (invoke! listener :on-message connection [(.-data e)])
                       (emit :message e (.-data e)))
           "error" (fn [e]
                     (invoke! listener :on-error connection [e])
                     (emit :error e nil))
           "close"
             (fn [e]
               (try (when @opened?
                      (invoke! listener :on-close connection [(.-code e) (.-reason e)]))
                    (emit :close e nil)
                    (finally (cleanup))))}]
    (set! (.-binaryType socket) "arraybuffer")
    (doseq [[type f] handlers]
      (.addEventListener socket type f)
      (swap! subscriptions conj [type f]))
    {:connection connection :dispose! cleanup}))
