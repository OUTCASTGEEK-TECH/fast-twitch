(ns native-demo.http
  (:require [cljs.core :refer [await]]
            [fast-twitch.routing :as routing]
            [fast-twitch.client.core :as client]
            [fast-twitch.server.core :as server])
  (:refer-global :only [Promise console]))

(defn ^:async -main
  []
  (let [base (atom nil)
        listened (atom nil)
        ready (Promise. (fn [resolve _]
                          (reset! listened resolve)))
        handler (routing/routes [{:pattern "/origin"
                                  :handler (fn [_]
                                             (routing/response "origin body"))}
                                 {:pattern "/forward"
                                  :handler
                                    (^:async fn
                                     [_]
                                     (let [response (await (client/request!
                                                             {:url (str @base "/origin")
                                                              :request-method :get
                                                              :headers {}}))]
                                       (-> response
                                           (assoc :status 201)
                                           (assoc-in [:headers "x-forwarded"] "yes"))))}]
                                (fn [_]
                                  (routing/not-found "missing")))
        serving (server/listen! handler {:port 0 :on-listen @listened})]
    (try (reset! base (str "http://127.0.0.1:" (.-port (await ready))))
         (let [response (await (client/request! {:url (str @base "/forward")
                                                 :request-method :get
                                                 :headers {}}
                                                {:codec :text}))]
           (assert (= 201 (:status response)))
           (assert (= "yes" (get-in response [:headers "x-forwarded"])))
           (assert (= "origin body" (:body response)))
           (.log console "HTTP forwarding and map edits passed"))
         (finally (await (server/close! serving {:force true}))))))

(set! *main-cli-fn* -main)
