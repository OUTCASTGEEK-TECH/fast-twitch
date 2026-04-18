(ns ddc.middlewares.resource
  [:require
   [ddc.middlewares.file :as file]])

(defn resource-request
  ([request root-path]
   (resource-request request root-path {}))
  ([request root-path options]
   (file/file-request request root-path options)))

(defn wrap-resource
  ([handler root-path]
   (wrap-resource handler root-path {}))
  ([handler root-path options]
   (file/wrap-file handler root-path options)))
