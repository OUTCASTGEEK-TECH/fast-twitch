(ns fast-twitch.middlewares.content-length
  "Adds a Content-Length header when the response body size can be determined eagerly."
  [:require [fast-twitch.middlewares.common :as common]])

(defn- body-length
  "Returns the byte or character length for supported response body shapes."
  [body]
  (cond
    (nil? body) nil
    (string? body) (count body)
    (array? body) (aget body "byteLength")
    (sequential? body) (count (apply str body))
    :else nil))

(defn content-length-response
  "Adds Content-Length to a response when it is missing and calculable."
  [response _request]
  (if (or (nil? (:body response))
          (common/has-header? (:headers response) :content-length))
    response
    (if-let [length (body-length (:body response))]
      (common/assoc-header response "Content-Length" (str length))
      response)))

(defn wrap-content-length
  "Wraps a handler so its responses gain a Content-Length header when possible."
  [handler]
  (common/wrap-response handler content-length-response))
