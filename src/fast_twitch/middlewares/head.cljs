(ns fast-twitch.middlewares.head
  "Treats HEAD requests like GET requests while omitting the response body."
  [:require [fast-twitch.middlewares.common :as common]
            [fast-twitch.util.http.body :as body]])

(defn head-request
  "Transforms a HEAD request into a GET request for handler execution."
  [request]
  (if (= :head (:request-method request)) (assoc request :request-method :get) request))

(defn head-response
  "Clears the response body when the original request method was HEAD."
  [response request]
  (if (= :head (:request-method request)) (body/replace-body response nil) response))

(defn wrap-head
  "Wraps a handler with HEAD request and response adjustments."
  [handler]
  (common/wrap-response (common/wrap-request handler head-request) head-response))
