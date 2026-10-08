(ns fast-twitch.util.async.settlement
  (:require [cljs.core :refer [await]])
  (:refer-global :only [Promise]))

(defn discard!
  [value]
  (when (and (some? value) (fn? (aget value "then")))
    (.catch (Promise.resolve value)
            (fn [_]
              nil))))

(defn ^:async callback-promise
  [invoke transform]
  (let [completion (Promise.withResolvers)
        settled? (atom false)
        settle (fn [deliver value]
                 (if (compare-and-set! settled? false true)
                   (deliver value)
                   (discard! value)))
        respond (fn [value]
                  (settle (.-resolve completion) value))
        raise (fn [error]
                (settle (.-reject completion) error))]
    (try (discard! (invoke respond raise))
         (catch :default error (settle (.-reject completion) error)))
    (await (transform (await (.-promise completion))))))
