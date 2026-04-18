(ns fast-twitch.middlewares.resource
  "Provides resource-style static asset serving through the file middleware."
  [:require
   [fast-twitch.middlewares.file :as file]])

(defn resource-request
  "Attempts to build a static resource response for the current request."
  ([request root-path]
   (resource-request request root-path {}))
  ([request root-path options]
   (file/file-request request root-path options)))

(defn wrap-resource
  "Wraps a handler with static resource serving for a given root path."
  ([handler root-path]
   (wrap-resource handler root-path {}))
  ([handler root-path options]
   (file/wrap-file handler root-path options)))
