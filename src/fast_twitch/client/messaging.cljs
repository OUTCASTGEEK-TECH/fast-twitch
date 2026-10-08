(ns fast-twitch.client.messaging
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.codecs.json :as json]
            [fast-twitch.util.validation :as v])
  (:refer-global :only [ArrayBuffer globalThis]))

(def Options
  (m/schema [:map {:closed true} [:codec {:optional true} [:enum :native :json]]
             [:on-event {:optional true} [:maybe contracts/Function]]
             [:owned? {:optional true} :boolean]]))

(def ^:private Transfer
  (m/schema
    [:and
     [:vector
      [:fn
       #(or (instance? ArrayBuffer %)
            (when-let [port (aget globalThis "MessagePort")]
              (instance? port %)))]] [:fn #(= (count %) (count (set %)))]]))

(mx/defn ^{:dynamic true :private true} post!
  [port value :- [:not coll?] transfer :- Transfer]
  (.postMessage port value (into-array transfer)))

(mx/defn ^:dynamic wrap-port
  [native {:keys [codec on-event owned?] :or {codec :native owned? true}} :- Options]
  (let [closed? (atom false)
        message (fn [event]
                  (when (and on-event (not @closed?))
                    (let [event-map {:type :message
                                     :fast-twitch.messaging/port native
                                     :fast-twitch.messaging/event event
                                     :data (.-data event)
                                     :ports (vec (.-ports event))}
                          decoded (if (= codec :json)
                                    (try (update event-map :data json/decode)
                                         (catch :default error
                                           {:type :error
                                            :code :fast-twitch.codec/invalid-json
                                            :error error
                                            :fast-twitch.messaging/port native}))
                                    event-map)]
                      (on-event decoded))))
        error (fn [event]
                (when (and on-event (not @closed?))
                  (on-event {:type :messageerror
                             :fast-twitch.messaging/port native
                             :fast-twitch.messaging/event event})))
        post (fn [value transfer]
               (when @closed?
                 (v/fail! :fast-twitch.messaging/closed :post-message! [] :open-port))
               (post! native
                      (if (= codec :json) (json/encode value) value)
                      transfer))
        close (fn []
                (when (compare-and-set! closed? false true)
                  (try (try (.removeEventListener native "message" message)
                            (finally (.removeEventListener native "messageerror" error)))
                       (finally (when owned? (.close native))))))]
    (try
      (.addEventListener native "message" message)
      (.addEventListener native "messageerror" error)
      (.start native)
      {:fast-twitch.messaging/port native :post! post :close! close}
      (catch :default error
        (try (close) (catch :default _ nil))
        (throw error)))))

(mx/defn ^:dynamic create-channel!
  ([]
   (create-channel! {} {}))
  ([options1 :- Options options2 :- Options]
   (let [constructor (aget globalThis "MessageChannel")
         _ (when-not (fn? constructor) (v/capability! {} :message-channel))
         native (new constructor)
         first-port (atom nil)]
     (try (reset! first-port (wrap-port (.-port1 native) options1))
          {:fast-twitch.messaging/channel native
           :port1 @first-port
           :port2 (wrap-port (.-port2 native) options2)}
          (catch :default error
            (when @first-port
              (try ((req! @first-port :close!)) (catch :default _ nil)))
            (doseq [port (cond-> []
                           (not (:owned? options1 true)) (conj (.-port1 native))
                           (or (nil? @first-port) (not (:owned? options2 true)))
                             (conj (.-port2 native)))]
              (try (.close port) (catch :default _ nil)))
            (throw error))))))

(set! wrap-port (contracts/instrument 'fast-twitch.client.messaging/wrap-port wrap-port))

(set! post! (contracts/instrument 'fast-twitch.client.messaging/post! post!))

(set! create-channel!
      (contracts/instrument 'fast-twitch.client.messaging/create-channel!
                            create-channel!))
