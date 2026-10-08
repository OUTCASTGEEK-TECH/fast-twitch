(ns fast-twitch.websocket-client-consumer
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.core :as client])
  (:require-macros [fast-twitch.macros :refer [env-var]])
  (:refer-global :only
                 [globalThis Promise Uint8Array AbortController console setTimeout
                  clearTimeout]))

(defn ^:async -main
  []
  (let [socket (atom nil)
        controller (AbortController.)
        receive (atom nil)
        ended (atom nil)
        closed (Promise. (fn [resolve _]
                           (reset! ended resolve)))
        timer (atom nil)
        timed-out (Promise. (fn [_ reject]
                              (reset! timer
                                (setTimeout
                                  #(do
                                     (.abort controller
                                             "Native WebSocket client fixture timed out")
                                     (reject "Native WebSocket client fixture timed out"))
                                  5000))))
        wait! (^:async fn
               [pending]
               (await (Promise.race #js [pending timed-out])))]
    (try (reset! socket (await (wait! (client/connect! {:url (env-var "FT_WS_URL")}
                                                       {:transport :websocket
                                                        :signal (.-signal controller)
                                                        :listener
                                                          {:on-message (fn [_ value]
                                                                         (@receive value))
                                                           :on-close (fn [_ _ _]
                                                                       (@ended nil))}}))))
         (when-not (identical? (req! @socket :fast-twitch.websocket/socket)
                               (req! @socket :socket))
           (throw (ex-info "Native socket identity lost" {})))
         (let [reply (Promise. (fn [resolve _]
                                 (reset! receive resolve)))]
           (client/send! @socket "native client")
           (when-not (= "native client" (await (wait! reply)))
             (throw (ex-info "Text echo changed" {}))))
         (let [reply (Promise. (fn [resolve _]
                                 (reset! receive resolve)))
               bytes (Uint8Array. #js [99 1 2 3 88])]
           (client/send! @socket {:type :binary :data (.subarray bytes 1 4)})
           (when-not (= [1 2 3] (vec (Uint8Array. (await (wait! reply)))))
             (throw (ex-info "Sliced binary echo changed" {}))))
         (client/close! @socket)
         (await (wait! closed))
         (.log console "Native WebSocket client text/binary/identity/close PASSED")
         (catch :default error
           (.error console error)
           (when-let [process (aget globalThis "process")] (aset process "exitCode" 1)))
         (finally (when @socket (client/close! @socket)) (clearTimeout @timer)))))

(set! *main-cli-fn* -main)
