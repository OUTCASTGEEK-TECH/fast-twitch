(ns fast-twitch.integration.browser-runner
  (:refer-clojure :exclude [run!])
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.http :as http]
            [fast-twitch.client.core :as client]
            [fast-twitch.client.websocket :as websocket]
            [fast-twitch.transports.websocket.connection :as socket]
            [fast-twitch.client.sse.event-source :as sse]
            [fast-twitch.client.sse.fetch :as fetch-sse]
            [fast-twitch.client.messaging :as messaging])
  (:refer-global :only [Promise Error globalThis setTimeout clearTimeout]))

(defn check!
  [condition description]
  (when-not condition (throw (Error. description))))

(defn ^:async bounded
  [promise]
  (let [timer (atom nil)]
    (try (await (Promise.race #js [promise
                                   (Promise. (fn [_ reject]
                                               (reset! timer
                                                 (setTimeout
                                                   #(reject (Error.
                                                              "Browser fixture timeout"))
                                                   5000))))]))
         (finally (clearTimeout @timer)))))

(defn ^:export ^:async run!
  [base]
  (let [response (await (http/fetch!
                          {:url (str base "/http") :request-method :get :headers {}}))]
    (check! (= "hello" (await (client/read-body! response {:codec :text})))
            "Native Fetch"))
  (let [events (atom [])]
    (await (fetch-sse/connect! {:url (str base "/sse") :request-method :get :headers {}}
                               {:on-event #(swap! events conj %)}))
    (check! (= ["one" "two"] (mapv :data @events)) "Fetch SSE"))
  (let [handle (atom nil)
        result (Promise. (fn [resolve _]
                           (reset! handle (sse/connect! {:url (str base "/sse")
                                                         :event-types ["named"]
                                                         :on-event
                                                           #(when (= "named" (:event %))
                                                              (resolve (:data %)))}))))]
    (try (check! (= "two" (await (bounded result))) "Native EventSource named event")
         (finally ((:close! @handle)))))
  (let [handle (atom nil)
        resolve-message (atom nil)
        result (Promise. (fn [resolve _]
                           (reset! resolve-message resolve)))]
    (try (reset! handle (await (websocket/connect!
                                 {:url (.replace (str base "/ws") "http:" "ws:")
                                  :listener {:on-message (fn [_ message]
                                                           (@resolve-message
                                                            message))}})))
         (client/send! @handle "browser-echo")
         (check! (= "browser-echo" (await (bounded result))) "Native WebSocket")
         (finally (when @handle (socket/close! @handle)))))
  (let [channel (atom nil)
        result (Promise. (fn [resolve _]
                           (reset! channel (messaging/create-channel!
                                             {:codec :json}
                                             {:codec :json
                                              :on-event #(resolve (:data %))}))))]
    (try (client/send! (:port1 @channel) {:browser false})
         (check! (= {:browser false} (await (bounded result))) "Native ports")
         (finally (client/close! (:port1 @channel))
                  (client/close! (:port2 @channel)))))
  #js {:passed #js ["fetch" "fetch-sse" "event-source" "websocket" "ports"]
       :browser (aget (aget globalThis "navigator") "userAgent")})
