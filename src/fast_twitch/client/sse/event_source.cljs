(ns fast-twitch.client.sse.event-source
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.validation :as v])
  (:refer-global :only [globalThis]))

(def ^:private Options
  (m/schema
    [:map {:closed true} [:transport {:optional true} [:= :event-source]] [:url :string]
     [:with-credentials? {:optional true} :boolean]
     [:event-types {:optional true} [:vector :string]] [:on-event contracts/Function]]))

(mx/defn ^:dynamic connect!
  [options :- Options]
  (let [{:keys! [url on-event]
         :keys [with-credentials? event-types]
         :or {with-credentials? false event-types []}}
          options
        constructor (aget globalThis "EventSource")
        _ (when-not (fn? constructor)
            (v/fail! :fast-twitch.sse/unsupported-event-source
                     :event-source
                     []
                     :native-event-source-use-fetch-stream))
        source (new constructor url #js {:withCredentials with-credentials?})
        listeners (mapv (fn [type] [type
                                    (fn [e]
                                      (let [kind (case type
                                                   "open" :open
                                                   "error" :error
                                                   :message)]
                                        (on-event
                                          (cond-> {:type kind :fast-twitch.sse/event e}
                                            (= kind :message)
                                              (assoc :data
                                                (.-data e) :event
                                                (.-type e)
                                                  :id
                                                (.-lastEventId e)
                                                  :origin
                                                (.-origin e))))))])
                    (distinct (concat ["open" "message" "error"] event-types)))
        closed? (atom false)]
    (doseq [[type f] listeners] (.addEventListener source type f))
    {:fast-twitch.sse/event-source source
     :state #(.-readyState source)
     :close! (fn []
               (when (compare-and-set! closed? false true)
                 (doseq [[type f] listeners] (.removeEventListener source type f))
                 (.close source)))}))

(set! connect!
      (contracts/instrument 'fast-twitch.client.sse.event-source/connect! connect!))
