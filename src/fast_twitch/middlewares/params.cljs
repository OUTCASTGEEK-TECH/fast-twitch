(ns fast-twitch.middlewares.params
  "Parses query strings and URL-encoded form bodies into request parameter maps."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Promise Response URLSearchParams]])

(defn- assoc-param
  "Associates a parameter value, turning repeated keys into vectors."
  [params k v]
  (update params k
          (fn [old]
            (cond
              (nil? old) v
              (vector? old) (conj old v)
              :else [old v]))))

(defn- params-map
  "Converts URLSearchParams entries into the request parameter map shape."
  [url-params]
  (reduce (fn [params entry]
            (assoc-param params (aget entry 0) (aget entry 1)))
          {}
          (.entries url-params)))

(defn- parse-params
  "Parses a query-string-like value into a parameter map."
  [s]
  (params-map (URLSearchParams. (or s ""))))

(defn- form-urlencoded?
  "Returns true when the request body uses URL-encoded form semantics."
  [request]
  (when-let [content-type (common/header-value (:headers request) :content-type)]
    (str/includes? (str/lower-case content-type)
                   "application/x-www-form-urlencoded")))

(defn assoc-query-params
  "Associates parsed query parameters onto the request."
  ([request]
   (assoc-query-params request nil))
  ([request _encoding]
   (let [query-params (parse-params (:query-string request))]
     (assoc request
            :query-params query-params
            :params (merge (:params request) query-params)))))

(defn assoc-form-params
  "Associates parsed URL-encoded form parameters onto the request."
  ([request]
   (assoc-form-params request nil))
  ([request _encoding]
   (if (and (:body request) (form-urlencoded? request))
     (-> (Response. (:body request))
         (.text)
         (.then (fn [body]
                  (let [form-params (parse-params body)]
                    (assoc request
                           :form-params form-params
                           :params (merge (:params request) form-params))))))
     (assoc request
            :form-params {}
            :params (or (:params request) {})))))

(defn params-request
  "Parses query and URL-encoded form parameters for a request."
  ([request]
   (params-request request {}))
  ([request options]
   (let [request (assoc-query-params request (:encoding options))
         request* (assoc-form-params request (:encoding options))]
     (if (common/promise? request*)
       (.then request*
              #(assoc % :params (merge (:query-params %) (:form-params %))))
       (assoc request*
              :params (merge (:query-params request*) (:form-params request*)))))))

(defn wrap-params
  "Wraps a handler so query and form parameters are available on the request."
  ([handler]
   (wrap-params handler {}))
  ([handler options]
   (common/wrap-request handler #(params-request % options))))
