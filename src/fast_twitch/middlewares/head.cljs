(ns fast-twitch.middlewares.head
  "Treats HEAD requests like GET requests while omitting the response body."
  [:require [fast-twitch.middlewares.common :as common]])

(defn head-request
  "Transforms a HEAD request into a GET request for handler execution."
  [request]
  (if (= :head (:request-method request))
    (assoc request :request-method :get)
    request))

(defn head-response
  "Clears the response body when the original request method was HEAD."
  [response request]
  (if (= :head (:request-method request))
    (assoc response :body nil)
    response))

(defn wrap-head
  "Wraps a handler with HEAD request and response adjustments."
  [handler]
  (common/wrap-request-response handler head-request head-response))
