(ns ddc.middlewares.ssl
  [:require
   [ddc.middlewares.common :as common]])

(defn- https-url [request]
  (str "https://"
       (:server-name request)
       (:uri request)
       (when-let [query-string (:query-string request)]
         (str "?" query-string))))

(defn wrap-ssl-redirect
  ([handler]
   (wrap-ssl-redirect handler {}))
  ([handler _options]
   (fn
     ([request]
      (if (= :https (:scheme request))
        (handler request)
        {:status 301
         :headers {"Location" (https-url request)}
         :body ""}))
     ([request respond raise]
      (if (= :https (:scheme request))
        (handler request respond raise)
        (respond {:status 301
                  :headers {"Location" (https-url request)}
                  :body ""}))))))

(defn hsts-response
  ([response request]
   (hsts-response response request {}))
  ([response request options]
   (if (= :https (:scheme request))
     (common/assoc-header response
                          "Strict-Transport-Security"
                          (str "max-age=" (or (:max-age options) 31536000)
                               (when (:include-subdomains? options)
                                 "; includeSubDomains")
                               (when (:preload? options)
                                 "; preload")))
     response)))

(defn wrap-hsts
  ([handler]
   (wrap-hsts handler {}))
  ([handler options]
   (common/wrap-response handler #(hsts-response %1 %2 options))))

(defn wrap-ssl
  ([handler]
   (wrap-ssl handler {}))
  ([handler options]
   (cond-> handler
     (:ssl-redirect? options)
     (wrap-ssl-redirect options)

     (:hsts? options)
     (wrap-hsts (:hsts options)))))
