(ns ddc.middlewares.head
  [:require [ddc.middlewares.common :as common]])

(defn head-request [request]
  (if (= :head (:request-method request))
    (assoc request :request-method :get)
    request))

(defn head-response [response request]
  (if (= :head (:request-method request))
    (assoc response :body nil)
    response))

(defn wrap-head [handler]
  (common/wrap-request-response handler head-request head-response))
