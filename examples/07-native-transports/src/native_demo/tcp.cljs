(ns native-demo.tcp
  (:refer-clojure :exclude [run!])
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.core :as client]
            [fast-twitch.server.core :as server])
  (:refer-global :only [Uint8Array console]))

(defn ^:async run!
  [runtime]
  (let [accepted (atom nil)
        configuration
          {:transport :tcp :runtime runtime :codec :bytes :streaming {:max-bytes 32}}
        listening (await (server/listen! (fn [connection]
                                           (reset! accepted connection)
                                           ((^:async fn
                                             []
                                             (try (await (client/send! connection
                                                                       (await
                                                                         (client/receive!
                                                                           connection))))
                                                  (await (client/half-close! connection))
                                                  (catch :default _ nil)))))
                                         (assoc configuration
                                           :hostname "127.0.0.1"
                                           :port 0)))
        connection (await (client/connect! {:hostname "127.0.0.1" :port (:port listening)}
                                           configuration))]
    (try (await (client/send! connection (Uint8Array. #js [4 5 6])))
         (await (client/half-close! connection))
         (assert (= [4 5 6] (vec (await (client/receive! connection)))))
         (.log console "Configured client/server TCP loopback passed")
         (finally (client/close! connection)
                  (when @accepted (server/close! @accepted))
                  (await (server/close! listening))))))
