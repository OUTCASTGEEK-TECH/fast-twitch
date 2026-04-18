(ns fast-twitch.middlewares.ssl
  "Redirects insecure traffic and adds strict transport security headers when configured."
  [:require
   [fast-twitch.middlewares.common :as common]])

(defn- https-url
  "Builds the HTTPS URL for the current request."
  [request]
  (str "https://"
       (:server-name request)
       (:uri request)
       (when-let [query-string (:query-string request)]
         (str "?" query-string))))

(defn wrap-ssl-redirect
  "Wraps a handler so non-HTTPS requests receive a permanent redirect."
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
  "Adds a Strict-Transport-Security header to HTTPS responses."
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
  "Wraps a handler so HTTPS responses include strict transport security metadata."
  ([handler]
   (wrap-hsts handler {}))
  ([handler options]
   (common/wrap-response handler #(hsts-response %1 %2 options))))

(defn wrap-ssl
  "Composes HTTPS redirect and strict transport security behavior from options."
  ([handler]
   (wrap-ssl handler {}))
  ([handler options]
   (cond-> handler
     (:ssl-redirect? options)
     (wrap-ssl-redirect options)

     (:hsts? options)
     (wrap-hsts (:hsts options)))))
