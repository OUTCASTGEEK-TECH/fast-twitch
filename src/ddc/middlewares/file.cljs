(ns ddc.middlewares.file
  [:require
   [ddc.middlewares.common :as common]
   ["@std/http/file-server" :refer [serveDir]]])

(defn- serve-options [root-path options]
  (clj->js
   (cond-> {:fsRoot root-path
            :quiet (get options :quiet? true)}
     (:url-root options)
     (assoc :urlRoot (:url-root options))

     (contains? options :index-files?)
     (assoc :showIndex (:index-files? options))

     (contains? options :show-dotfiles?)
     (assoc :showDotfiles (:show-dotfiles? options))

     (:headers options)
     (assoc :headers (:headers options)))))

(defn file-request
  ([request root-path]
   (file-request request root-path {}))
  ([request root-path options]
   (if (#{:get :head} (:request-method request))
     (-> (serveDir (common/ring->fetch-request request)
                   (serve-options root-path options))
         (.then (fn [response]
                  (when (not= 404 (aget response "status"))
                    (common/fetch-response->ring response)))))
     (common/promise nil))))

(defn- handler-response [handler request]
  (let [response (handler request)]
    (if (common/promise? response)
      response
      (common/promise response))))

(defn wrap-file
  ([handler root-path]
   (wrap-file handler root-path {}))
  ([handler root-path options]
   (fn
     ([request]
      (if (:prefer-handler? options)
        (handler-response handler request)
        (-> (file-request request root-path options)
            (.then #(or % (handler request))))))
     ([request respond raise]
      (if (:prefer-handler? options)
        (handler request respond raise)
        (-> (file-request request root-path options)
            (.then (fn [response]
                     (if response
                       (respond response)
                       (handler request respond raise))))
            (.catch raise)))))))
