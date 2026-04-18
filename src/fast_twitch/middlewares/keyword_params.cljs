(ns fast-twitch.middlewares.keyword-params
  "Converts string parameter keys into keywords throughout parsed parameter maps."
  [:require [fast-twitch.middlewares.common :as common]])

(defn- keyword-key
  "Converts supported map keys into keywords."
  [k]
  (cond
    (keyword? k) k
    (string? k) (keyword k)
    :else k))

(defn- keywordize
  "Recursively keywordizes map keys while preserving vectors and values."
  [x]
  (cond
    (map? x)
    (into {}
          (map (fn [[k v]]
                 [(keyword-key k) (keywordize v)]))
          x)

    (vector? x)
    (mapv keywordize x)

    :else
    x))

(defn keyword-params-request
  "Keywordizes parameter maps already associated with the request."
  ([request]
   (keyword-params-request request {}))
  ([request _options]
   (cond-> request
     (:params request)
     (update :params keywordize)

     (:query-params request)
     (update :query-params keywordize)

     (:form-params request)
     (update :form-params keywordize)

     (:multipart-params request)
     (update :multipart-params keywordize))))

(defn wrap-keyword-params
  "Wraps a handler so parsed parameters use keyword keys."
  ([handler]
   (wrap-keyword-params handler {}))
  ([handler options]
   (common/wrap-request handler #(keyword-params-request % options))))
