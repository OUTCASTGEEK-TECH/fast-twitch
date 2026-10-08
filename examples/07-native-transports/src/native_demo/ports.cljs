(ns native-demo.ports
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.core :as client])
  (:refer-global :only [Promise console]))

(defn ^:async -main
  []
  (let [channel (atom nil)
        received (Promise. (fn [resolve _]
                             (reset! channel (client/message-channel!
                                               {:codec :json
                                                :on-event #(resolve (:data %))}))))]
    (try (client/send! (:port1 @channel) {"values" [false nil 42]})
         (assert (= {"values" [false nil 42]} (await received)))
         (.log console "MessageChannel with an explicit JSON codec passed")
         (finally (client/close! (:port1 @channel)) (client/close! (:port2 @channel))))))

(set! *main-cli-fn* -main)
