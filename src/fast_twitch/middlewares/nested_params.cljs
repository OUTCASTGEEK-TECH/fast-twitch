(ns fast-twitch.middlewares.nested-params
  [:require [fast-twitch.middlewares.common :as common]])

(defn parse-nested-keys [param-name]
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

(defn- put-value [old value]
  (cond
    (nil? old) value
    (vector? old) (conj old value)
    :else [old value]))

(defn- assoc-nested [m keys value]
  (let [k (first keys)
        more (next keys)]
    (if more
      (if (= "" k)
        (conj (or m []) (assoc-nested nil more value))
        (update (or m {}) k #(assoc-nested % more value)))
      (if (= "" k)
        (conj (or m []) value)
        (update (or m {}) k put-value value)))))

(defn- nested-map [params key-parser]
  (reduce (fn [m [k v]]
            (assoc-nested m (key-parser k) v))
          {}
          params))

(defn nested-params-request
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
  ([handler]
   (wrap-nested-params handler {}))
  ([handler options]
   (common/wrap-request handler #(nested-params-request % options))))
