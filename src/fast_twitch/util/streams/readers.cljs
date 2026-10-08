(ns fast-twitch.util.streams.readers
  (:require [cljs.core :refer [await]]
            [malli.experimental :as mx]
            [malli.util :as mu]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.codecs.json :as json]
            [fast-twitch.util.binary :as binary])
  (:refer-global :only [Uint8Array TextDecoder Promise]))

(defn- reader!
  [stream mode]
  (if (= mode :byob) (.getReader stream #js {:mode "byob"}) (.getReader stream)))

(defn- ^:async reduce-reader!
  [reader f initial finish
   {:keys [owned? signal mode chunk-bytes]
    :or {owned? true mode :default chunk-bytes 65536}}]
  (let [reject-abort (atom nil)
        abort (fn []
                (when-let [reject @reject-abort] (reject (.-reason signal))))]
    (try (when signal
           (.addEventListener signal "abort" abort #js {:once true})
           (when (.-aborted signal) (abort)))
         (loop [acc initial]
           (when (and signal (.-aborted signal)) (throw (.-reason signal)))
           (let [reading (if (= mode :byob)
                           (.read reader (Uint8Array. chunk-bytes))
                           (.read reader))
                 result (if signal
                          (let [cancelled (Promise.withResolvers)]
                            (reset! reject-abort (.-reject cancelled))
                            (when (.-aborted signal)
                              (.reject cancelled (.-reason signal)))
                            (await (Promise.race #js [(.-promise cancelled) reading])))
                          (await reading))]
             (reset! reject-abort nil)
             (when (and signal (.-aborted signal)) (throw (.-reason signal)))
             (if (.-done result)
               (let [result (await (finish acc))]
                 (if (reduced? result) @result result))
               (let [next (await (f acc (.-value result)))]
                 (if (reduced? next)
                   (do (when owned? (await (.cancel reader))) @next)
                   (recur next))))))
         (catch :default error
           (when owned? (try (await (.cancel reader error)) (catch :default _ nil)))
           (throw error))
         (finally (when signal (.removeEventListener signal "abort" abort))
                  (.releaseLock reader)))))

(def ^:private ReductionOptions
  (mu/assoc contracts/ReaderOptions [:codec {:optional true}] [:enum :bytes :text]))

(mx/defn ^{:dynamic true :async true} reduce!
  :-
  contracts/PromiseResult
  ([stream f :- contracts/Function initial]
   (reduce! stream f initial {}))
  ([stream f :- contracts/Function initial options :- ReductionOptions]
   (let [{:keys [mode codec] :or {mode :default codec :bytes}} options
         reader (reader! stream mode)
         decoder (when (= codec :text) (TextDecoder. "utf-8" #js {:fatal true}))
         emit (fn [acc text]
                (if (empty? text) acc (f acc text)))
         consume (if decoder
                   (fn [acc chunk]
                     (emit acc (.decode decoder (binary/bytes chunk) #js {:stream true})))
                   f)
         finish (if decoder
                  (fn [acc]
                    (emit acc (.decode decoder)))
                  identity)]
     (await (reduce-reader! reader consume initial finish options)))))

(def ^:private ReadOptions
  (mu/assoc contracts/CollectOptions [:codec {:optional true}] contracts/ReadCodec))

(mx/defn ^{:dynamic true :async true} read!
  :-
  contracts/PromiseResult
  ([stream]
   (read! stream {}))
  ([stream
    {:keys [max-bytes mode codec]
     :or {max-bytes 1048576 mode :default codec :bytes}
     :as options} :- ReadOptions]
   (let [{:keys! [chunks size]}
           (if stream
             (await
               (reduce-reader!
                 (reader! stream mode)
                 (fn [{:keys! [chunks size]} chunk]
                   (let [chunk (binary/bytes chunk)
                         size (+ size (.-byteLength chunk))]
                     (when (> size max-bytes)
                       (v/fail! :fast-twitch.stream/limit-exceeded :read! [] max-bytes))
                     {:chunks (conj chunks (.slice chunk)) :size size}))
                 {:chunks [] :size 0}
                 identity
                 options))
             {:chunks [] :size 0})
         out (Uint8Array. size)]
     (loop [offset 0
            chunks chunks]
       (when-let [chunk (first chunks)]
         (.set out chunk offset)
         (recur (+ offset (.-byteLength chunk)) (next chunks))))
     (if (= codec :bytes)
       out
       (let [text (.decode (TextDecoder. "utf-8" #js {:fatal true}) out)]
         (if (= codec :json) (json/decode text) text))))))

(defn read-body!
  ([message]
   (read-body! message {}))
  ([message options]
   (read! (:body message) options)))

(set! reduce! (contracts/instrument 'fast-twitch.util.streams.readers/reduce! reduce!))

(set! read! (contracts/instrument 'fast-twitch.util.streams.readers/read! read!))
