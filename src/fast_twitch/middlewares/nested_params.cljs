(ns fast-twitch.middlewares.nested-params
  "Turns bracketed parameter names into nested maps and vectors."
  [:require [fast-twitch.middlewares.common :as common]])

(defn parse-nested-keys
  "Splits a bracketed parameter name into its path segments."
  [param-name]
  (let [s (str param-name)]
    (loop [chars (seq s)
           token ""
           tokens []]
      (if-let [c (first chars)]
        (case c
          \[ (recur (next chars) "" (conj tokens token))
          \] (recur (next chars) token tokens)
          (recur (next chars) (str token c) tokens))
        (conj tokens token)))))

(defn- put-value
  "Appends repeated values while preserving a single initial value."
  [old value]
  (cond
    (nil? old) value
    (vector? old) (conj old value)
    :else [old value]))

(defn- assoc-nested
  "Associates a value into a nested structure described by key segments."
  [m keys value]
  (let [k (first keys)
        more (next keys)]
    (if more
      (if (= "" k)
        (conj (or m []) (assoc-nested nil more value))
        (update (or m {}) k #(assoc-nested % more value)))
      (if (= "" k)
        (conj (or m []) value)
        (update (or m {}) k put-value value)))))

(defn- nested-map
  "Builds a nested parameter map from flat key/value pairs."
  [params key-parser]
  (reduce (fn [m [k v]]
            (assoc-nested m (key-parser k) v))
          {}
          params))

(defn nested-params-request
  "Rewrites parsed parameter maps using nested structures."
  ([request]
   (nested-params-request request {}))
  ([request options]
   (let [key-parser (or (:key-parser options) parse-nested-keys)]
     (cond-> request
       (:params request)
       (update :params nested-map key-parser)

       (:query-params request)
       (update :query-params nested-map key-parser)

       (:form-params request)
       (update :form-params nested-map key-parser)

       (:multipart-params request)
       (update :multipart-params nested-map key-parser)))))

(defn wrap-nested-params
  "Wraps a handler so bracketed parameter names become nested data."
  ([handler]
   (wrap-nested-params handler {}))
  ([handler options]
   (common/wrap-request handler #(nested-params-request % options))))
