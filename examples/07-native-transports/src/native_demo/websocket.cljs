(ns native-demo.websocket
  (:require [cljs.core :refer [await]]
            [fast-twitch.routing :as routing]
            [fast-twitch.client.core :as client]
            [fast-twitch.server.core :as server])
  (:refer-global :only [Promise console globalThis]))

(defn ^:async -main
  []
  (if-not (or (aget globalThis "Deno") (aget globalThis "Bun"))
    (.log console "Native server upgrade is available in the Deno and Bun profiles")
    (let [listened (atom nil)
          ready (Promise. (fn [resolve _]
                            (reset! listened resolve)))
          handler (routing/routes [{:pattern "/socket"
                                    :handler (fn [_]
                                               {:ring.websocket/listener
                                                  {:on-message (fn [connection value]
                                                                 (client/send!
                                                                   connection
                                                                   value))}})}]
                                  (fn [_]
                                    (routing/not-found "missing")))
          serving (server/listen! handler {:port 0 :on-listen @listened})
          connection (atom nil)]
      (try (let [received (atom nil)
                 reply (Promise. (fn [resolve _]
                                   (reset! received resolve)))
                 url (str "ws://127.0.0.1:" (.-port (await ready)) "/socket")]
             (reset! connection (await (client/connect!
                                         {:url url}
                                         {:transport :websocket
                                          :listener {:on-message (fn [_ value]
                                                                   (@received value))}})))
             (client/send! @connection "hello")
             (assert (= "hello" (await reply)))
             (.log console "Native WebSocket through existing routes and serve passed"))
           (finally (when @connection (client/close! @connection))
                    (await (server/close! serving {:force true})))))))

(set! *main-cli-fn* -main)
