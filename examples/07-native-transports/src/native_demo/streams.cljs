(ns native-demo.streams
  (:require [cljs.core :refer [await]]
            [fast-twitch.client.core :as client])
  (:refer-global :only [AbortController ReadableStream Uint8Array console]))

(defn ^:async -main
  []
  (let [controller (AbortController.)
        cancelled (atom 0)
        stream (ReadableStream. #js {:pull #(.enqueue % (Uint8Array. #js [1 2 3]))
                                     :cancel #(swap! cancelled inc)}
                                #js {:highWaterMark 0})]
    (try (await (client/reduce-stream! stream
                                       (fn [n chunk]
                                         (.abort controller :finished)
                                         (+ n (.-byteLength chunk)))
                                       0
                                       {:signal (.-signal controller) :owned? true}))
         (catch :default reason (assert (= :finished reason))))
    (assert (= 1 @cancelled))
    (assert (not (.-locked stream)))
    (.log console "Owned stream cancellation released the reader"))
  (let [stream (ReadableStream.
                 #js {:start (fn [controller]
                               (.enqueue controller (Uint8Array. #js [240 159]))
                               (.enqueue controller (Uint8Array. #js [152 128]))
                               (.close controller))})
        text (await (client/reduce-stream! stream str "" {:codec :text}))]
    (assert (= "😀" text))
    (.log console "Incremental text preserved a split UTF-8 character")))

(set! *main-cli-fn* -main)
