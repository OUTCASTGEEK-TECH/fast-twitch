(ns fast-twitch.middlewares.x-headers
  [:require [fast-twitch.middlewares.common :as common]])

(defn- frame-options-value [value]
  (case value
    :deny "DENY"
    :sameorigin "SAMEORIGIN"
    value))

(defn x-headers-response
  ([response request]
   (x-headers-response response request {}))
  ([response _request options]
   (cond-> response
     (:content-type-options options)
     (common/assoc-header "X-Content-Type-Options"
                          (:content-type-options options))

     (:frame-options options)
     (common/assoc-header "X-Frame-Options"
                          (frame-options-value (:frame-options options)))

     (:xss-protection options)
     (common/assoc-header "X-XSS-Protection"
                          (:xss-protection options)))))

(defn wrap-x-headers
  ([handler]
   (wrap-x-headers handler {}))
  ([handler options]
   (common/wrap-response handler #(x-headers-response %1 %2 options))))
