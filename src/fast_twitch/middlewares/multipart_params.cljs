(ns fast-twitch.middlewares.multipart-params
  "Parses multipart form bodies and exposes uploads in a request-friendly map shape."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Promise]])

(def content-too-large-response
  {:status 413
   :headers {"Content-Type" "text/plain"}
   :body "Content Too Large\n"})

(defn content-too-large-handler
  "Returns a standard 413 response in both sync and async handler forms."
  ([_request]
   content-too-large-response)
  ([_request respond _raise]
   (respond content-too-large-response)))

(defn- multipart?
  "Returns true when the request uses multipart form data."
  [request]
  (when-let [content-type (common/header-value (:headers request) :content-type)]
    (str/includes? (str/lower-case content-type) "multipart/form-data")))

(defn- file-value
  "Builds the upload map used for multipart file entries."
  [file]
  {:filename (aget file "name")
   :content-type (aget file "type")
   :size (aget file "size")
   :tempfile file
   :file file})

(defn- form-value
  "Normalizes a multipart field into either a string or upload map."
  [value]
  (if (string? value)
    value
    (file-value value)))

(defn- assoc-param
  "Associates a multipart value, grouping repeated keys into vectors."
  [params k v]
  (update params k
          (fn [old]
            (cond
              (nil? old) v
              (vector? old) (conj old v)
              :else [old v]))))

(defn- form-data-map
  "Converts FormData entries into the multipart parameter map shape."
  [form-data]
  (reduce (fn [params entry]
            (assoc-param params (aget entry 0) (form-value (aget entry 1))))
          {}
          (.entries form-data)))

(defn parse-multipart-params
  "Parses multipart parameters from the request body."
  ([request]
   (parse-multipart-params request {}))
  ([request _options]
   (if (and (:body request) (multipart? request))
     (-> (common/ft->fetch-request request)
         (.formData)
         (.then form-data-map))
     (Promise.resolve {}))))

(defn multipart-params-request
  "Associates parsed multipart parameters onto the request."
  ([request]
   (multipart-params-request request {}))
  ([request options]
   (-> (parse-multipart-params request options)
       (.then (fn [multipart-params]
                (assoc request
                       :multipart-params multipart-params
                       :params (merge (:params request) multipart-params)))))))

(defn wrap-multipart-params
  "Wraps a handler so multipart form data is available on the request."
  ([handler]
   (wrap-multipart-params handler {}))
  ([handler options]
   (common/wrap-request handler #(multipart-params-request % options))))
