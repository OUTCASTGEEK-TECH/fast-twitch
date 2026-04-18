(ns ddc.middlewares.content-length
  [:require [ddc.middlewares.common :as common]])

(defn- body-length [body]
  (cond
    (nil? body) nil
    (string? body) (count body)
    (array? body) (aget body "byteLength")
    (sequential? body) (count (apply str body))
    :else nil))

(defn content-length-response [response _request]
  (if (or (nil? (:body response))
          (common/has-header? (:headers response) :content-length))
    response
    (if-let [length (body-length (:body response))]
      (common/assoc-header response "Content-Length" (str length))
      response)))

(defn wrap-content-length [handler]
  (common/wrap-response handler content-length-response))
