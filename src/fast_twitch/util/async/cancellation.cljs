(ns fast-twitch.util.async.cancellation
  (:require [cljs.core :refer [await]])
  (:refer-global :only [Promise]))

(defn aborted!
  [signal]
  (when (and signal (.-aborted signal)) (throw (.-reason signal))))

(defn ^:async await-owned!
  "Rejects promptly on abort; disposes a result arriving after cancellation.
  Native cancellation is separate: the caller passes signal into native APIs
  that accept it. For APIs without cancellation, late owned handles are closed."
  [pending signal dispose!]
  (if-not signal
    (await pending)
    (let [state (atom :pending)
          cancelled (Promise.withResolvers)
          abort (fn []
                  (when (compare-and-set! state :pending :aborted)
                    (.reject cancelled (.-reason signal))))
          owned (^:async fn
                 []
                 (let [result (try (await pending)
                                   (catch :default error
                                     (compare-and-set! state :pending :failed)
                                     (throw error)))]
                   (if (compare-and-set! state :pending :delivered)
                     result
                     (do (try (await (dispose! result)) (catch :default _ nil))
                         (throw (.-reason signal))))))]
      (try (.addEventListener signal "abort" abort #js {:once true})
           (when (.-aborted signal) (abort))
           (await (Promise.race #js [(owned) (.-promise cancelled)]))
           (finally (.removeEventListener signal "abort" abort))))))
