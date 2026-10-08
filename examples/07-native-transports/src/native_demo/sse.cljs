(ns native-demo.sse
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.core :as client]
            [fast-twitch.server.core :as server]
            [fast-twitch.server.sse :as sse])
  (:refer-global :only [Promise console globalThis]))

(defn ^:async -main
  []
  (let [listened (atom nil)
        ready (Promise. (fn [resolve _]
                          (reset! listened resolve)))
        handler (fn [_]
                  (let [events (atom [{:data "one"} {:data "two" :event "named"}])]
                    (sse/response (fn []
                                    (let [event (first @events)]
                                      (swap! events next)
                                      event)))))
        serving (server/listen! handler {:port 0 :on-listen @listened})
        source (atom nil)]
    (try (let [url (str "http://127.0.0.1:" (.-port (await ready)) "/events")
               events (atom [])]
           (await (client/request! {:url url :request-method :get :headers {}}
                                   {:transport :sse :on-event #(swap! events conj %)}))
           (assert (= ["one" "two"] (mapv :data @events)))
           (.log console "Fetch stream SSE passed")
           (when (aget globalThis "EventSource")
             (let [named (Promise. (fn [resolve _]
                                     (reset! source (client/connect!
                                                      {:url url}
                                                      {:transport :event-source
                                                       :event-types ["named"]
                                                       :on-event
                                                         #(when (= "named" (:event %))
                                                            (resolve (:data %)))}))))]
               (assert (= "two" (await named)))
               (.log console "Native named EventSource passed"))))
         (finally (when @source (client/close! @source))
                  (await (server/close! serving {:force true}))))))

(set! *main-cli-fn* -main)
