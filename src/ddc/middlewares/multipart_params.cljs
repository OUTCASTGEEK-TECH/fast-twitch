(ns ddc.middlewares.multipart-params
  [:require
   [clojure.string :as str]
   [ddc.middlewares.common :as common]]
  [:refer-global :only [Promise]])

(def content-too-large-response
  {:status 413
   :headers {"Content-Type" "text/plain"}
   :body "Content Too Large\n"})

(defn content-too-large-handler
  ([_request]
   content-too-large-response)
  ([_request respond _raise]
   (respond content-too-large-response)))

(defn- multipart? [request]
  (when-let [content-type (common/header-value (:headers request) :content-type)]
    (str/includes? (str/lower-case content-type) "multipart/form-data")))

(defn- file-value [file]
  {:filename (aget file "name")
   :content-type (aget file "type")
   :size (aget file "size")
   :tempfile file
   :file file})

(defn- form-value [value]
  (if (string? value)
    value
    (file-value value)))

(defn- assoc-param [params k v]
  (update params k
          (fn [old]
            (cond
              (nil? old) v
              (vector? old) (conj old v)
              :else [old v]))))

(defn- form-data-map [form-data]
  (reduce (fn [params entry]
            (assoc-param params (aget entry 0) (form-value (aget entry 1))))
          {}
          (.entries form-data)))

(defn parse-multipart-params
  ([request]
   (parse-multipart-params request {}))
  ([request _options]
   (if (and (:body request) (multipart? request))
     (-> (common/ring->fetch-request request)
         (.formData)
         (.then form-data-map))
     (Promise.resolve {}))))

(defn multipart-params-request
  ([request]
   (multipart-params-request request {}))
  ([request options]
   (-> (parse-multipart-params request options)
       (.then (fn [multipart-params]
                (assoc request
                       :multipart-params multipart-params
                       :params (merge (:params request) multipart-params)))))))

(defn wrap-multipart-params
  ([handler]
   (wrap-multipart-params handler {}))
  ([handler options]
   (common/wrap-request handler #(multipart-params-request % options))))
