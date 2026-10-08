(ns fast-twitch.codecs.json
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.http.headers :as headers])
  (:refer-global :only [JSON Number Object]))

(def Value
  (m/schema
    [:schema
     {:registry {::value [:or :nil :string :boolean
                          [:and :double
                           [:fn
                            #(and (Number.isFinite %)
                                  (or (not (integer? %))
                                      (Number.isSafeInteger %)))]]
                          [:vector [:ref ::value]] [:map-of :string [:ref ::value]]]}}
     [:ref ::value]]))

(defn- native-value
  [value depth]
  (when (> depth 64)
    (throw (ex-info "JSON depth limit" {:code :fast-twitch.codec/limit-exceeded})))
  (cond (map? value) (let [out (Object.create nil)]
                       (doseq [[key item] value]
                         (aset out key (native-value item (inc depth))))
                       out)
        (vector? value) (into-array (map #(native-value % (inc depth)) value))
        :else value))

(mx/defn ^:dynamic encode
  :-
  :string
  [value :- Value]
  (JSON.stringify (native-value value 0)))

(defn- decoded-value
  [value depth]
  (when (> depth 64)
    (throw (ex-info "JSON depth limit" {:code :fast-twitch.codec/limit-exceeded})))
  ;; JSON.parse produces only primitives, arrays and ordinary objects. Traverse
  ;; properties as data, including constructor and __proto__, without type
  ;; predicates that inspect those properties or clj->js prototype assignment.
  (cond (or (nil? value) (string? value) (boolean? value) (number? value)) value
        (array? value) (mapv #(decoded-value % (inc depth)) (array-seq value))
        :else (reduce (fn [out key]
                        (assoc out key (decoded-value (aget value key) (inc depth))))
                {}
                (array-seq (Object.keys value)))))

(mx/defn ^:dynamic decode
  :-
  Value
  [text :- [:string {:max 1048576}]]
  (decoded-value (JSON.parse text) 0))

(defn encode-body
  [message]
  (cond-> (update message :body encode)
    (not (contains? (headers/normalized (:headers message)) "content-type"))
      (assoc-in [:headers "content-type"] "application/json")))

(set! encode (contracts/instrument 'fast-twitch.codecs.json/encode encode))

(set! decode (contracts/instrument 'fast-twitch.codecs.json/decode decode))
