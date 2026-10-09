(ns fast-twitch.codecs.json
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.http.headers :as headers])
  (:refer-global :only [JSON Number Object TextEncoder]))

(def Value
  (m/schema
    [:schema
     {:registry {::value [:or :nil :string :keyword :boolean
                          [:and :double
                           [:fn
                            #(and (Number.isFinite %)
                                  (or (not (integer? %))
                                      (Number.isSafeInteger %)))]]
                          [:vector [:ref ::value]] [:map-of :keyword [:ref ::value]]]}}
     [:ref ::value]]))

(defn- native-value
  "Projects full keyword spelling to ordinary JSON, rejecting key aliases."
  [value depth]
  (when (> depth 64)
    (throw (ex-info "JSON depth limit" {:code :fast-twitch.codec/limit-exceeded})))
  (cond (map? value) (let [out (Object.create nil)]
                       (doseq [[key item] value]
                         (let [key (subs (str key) 1)]
                           (when (Object.hasOwn out key)
                             (throw (ex-info "JSON key projection collision"
                                             {:code :fast-twitch.codec/key-collision
                                              :path []
                                              :expected :distinct-json-keys})))
                           (aset out key (native-value item (inc depth)))))
                       out)
        (vector? value) (into-array (map #(native-value % (inc depth)) value))
        (keyword? value) (subs (str value) 1)
        :else value))

(defn- bounded-text
  "Uses the same UTF-8 byte domain in both directions."
  [text]
  (when (> (.-byteLength (.encode (TextEncoder.) text)) 1048576)
    (throw (ex-info "JSON exceeds 1048576 UTF-8 bytes"
                    {:code :fast-twitch.codec/limit-exceeded
                     :path []
                     :expected :utf8-payload-budget})))
  text)

(mx/defn ^:dynamic encode
  :-
  :string
  [value :- Value]
  (bounded-text (JSON.stringify (native-value value 0))))

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
                        (assoc out
                          (keyword key) (decoded-value (aget value key) (inc depth))))
                {}
                (array-seq (Object.keys value)))))

(defn decode-native
  "Projects JSON received from native .json() APIs through the same keyword decoder."
  [value]
  (decoded-value value 0))

(mx/defn ^:dynamic decode
  :-
  Value
  [text :- :string]
  (decoded-value (JSON.parse (bounded-text text)) 0))

(defn encode-body
  [message]
  (cond-> (update message :body encode)
    (not (contains? (headers/normalized (:headers message)) "content-type"))
      (assoc-in [:headers "content-type"] "application/json")))

(set! encode (contracts/instrument 'fast-twitch.codecs.json/encode encode))

(set! decode (contracts/instrument 'fast-twitch.codecs.json/decode decode))
