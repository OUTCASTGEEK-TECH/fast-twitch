(ns fast-twitch.util.binary
  (:refer-clojure :exclude [bytes])
  (:require [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts])
  (:refer-global :only [ArrayBuffer Uint8Array]))

(defn binary?
  [x]
  (or (instance? ArrayBuffer x) (ArrayBuffer.isView x)))

(mx/defn ^:dynamic bytes
  [x :- [:fn binary?]]
  (if (instance? ArrayBuffer x)
    (Uint8Array. x)
    (Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))))

(set! bytes (contracts/instrument 'fast-twitch.util.binary/bytes bytes))
