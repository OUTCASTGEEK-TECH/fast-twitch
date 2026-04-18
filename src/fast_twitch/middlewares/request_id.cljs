(ns fast-twitch.middlewares.request-id
  "Generates and propagates request IDs for request correlation."
  [:require
   [cljs.nodejs :as nodejs]
   [fast-twitch.middlewares.common :as common]])

(def monotonic-ulid
  "The @std/ulid monotonic ULID generator used for default request IDs."
  (aget (nodejs/require "@std/ulid") "monotonicUlid"))

(def default-header-name
  "The default request header used to read and write request IDs."
  "X-Request-ID")

(defn generate-request-id
  "Generates a sortable request ID using @std/ulid."
  []
  (monotonic-ulid))

(defn request-id-request
  "Associates a request ID with the request, preserving an incoming ID when present."
  ([request]
   (request-id-request request {}))
  ([request options]
   (let [header-name (or (:header-name options) default-header-name)
         id (or (common/header-value (:headers request) header-name)
                ((or (:generator options) generate-request-id)))]
     (assoc request :request-id id))))

(defn request-id-response
  "Adds the request ID to the response headers when configured to do so."
  ([response request]
   (request-id-response response request {}))
  ([response request options]
   (let [header-name (or (:header-name options) default-header-name)]
     (if (and (not (false? (:response-header? options)))
              (:request-id request)
              (not (common/has-header? (:headers response) header-name)))
       (common/assoc-header response header-name (:request-id request))
       response))))

(defn wrap-request-id
  "Wraps a handler with request ID generation and response header propagation."
  ([handler]
   (wrap-request-id handler {}))
  ([handler options]
   (common/wrap-request-response
    handler
    #(request-id-request % options)
    #(request-id-response %1 %2 options))))
