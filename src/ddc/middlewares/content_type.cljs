(ns ddc.middlewares.content-type
  [:require
   [cljs.nodejs :as nodejs]
   [clojure.string :as str]
   [ddc.middlewares.common :as common]])

(def type-by-extension
  (aget (nodejs/require "@std/media-types/type-by-extension")
        "typeByExtension"))

(defn- extension [path]
  (when-let [file-name (last (str/split (or path "") "/"))]
    (when-let [idx (str/last-index-of file-name ".")]
      (subs file-name idx))))

(defn- body-path [body]
  (when (map? body)
    (or (:path body) (:filename body))))

(defn- mime-type [path options]
  (let [ext (extension path)
        mime-types (:mime-types options)]
    (or (get mime-types ext)
        (get mime-types (some-> ext (subs 1)))
        (when ext (type-by-extension ext))
        "application/octet-stream")))

(defn content-type-response
  ([response request]
   (content-type-response response request {}))
  ([response request options]
   (if (or (nil? (:body response))
           (common/has-header? (:headers response) :content-type))
     response
     (common/assoc-header
      response
      "Content-Type"
      (mime-type (or (body-path (:body response)) (:uri request)) options)))))

(defn wrap-content-type
  ([handler]
   (wrap-content-type handler {}))
  ([handler options]
   (common/wrap-response handler #(content-type-response %1 %2 options))))
