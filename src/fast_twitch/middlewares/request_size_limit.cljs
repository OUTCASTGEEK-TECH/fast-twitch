(ns fast-twitch.middlewares.request-size-limit
  "Rejects requests whose declared body size exceeds a configured limit."
  [:require [fast-twitch.middlewares.common :as common]])

(def default-error-response
  "The default response returned when a request body is too large."
  {:status 413
   :headers {"Content-Type" "text/plain"}
   :body "Content Too Large\n"})

(defn content-length
  "Returns the request Content-Length header as a number when it can be parsed."
  [request]
  (when-let [value (common/header-value (:headers request) :content-length)]
    (let [n (js/Number value)]
      (when-not (js/isNaN n)
        n))))

(defn request-too-large?
  "Returns true when the request declares a body larger than max-bytes."
  [request max-bytes]
  (when-let [length (content-length request)]
    (> length max-bytes)))

(defn request-size-limit-response
  "Builds the response returned for an oversized request."
  [request options]
  (if-let [handler (:error-handler options)]
    (handler request)
    (or (:error-response options) default-error-response)))

(defn wrap-request-size-limit
  "Wraps a handler with Content-Length based request size enforcement."
  ([handler]
   (wrap-request-size-limit handler {}))
  ([handler options]
   (let [options (if (number? options) {:max-bytes options} options)
         max-bytes (:max-bytes options)]
     (fn
       ([request]
        (if (and max-bytes (request-too-large? request max-bytes))
          (request-size-limit-response request options)
          (handler request)))
       ([request respond raise]
        (if (and max-bytes (request-too-large? request max-bytes))
          (respond (request-size-limit-response request options))
          (handler request respond raise)))))))
