(ns fast-twitch.middlewares.proxy-headers
  "Adapts request connection details from common forwarding headers."
  [:require [fast-twitch.middlewares.common :as common]])

(defn- forwarded-proto
  "Reads the first forwarded protocol value from request headers."
  [request]
  (some-> (common/header-value (:headers request) :x-forwarded-proto)
          (.split ",")
          (aget 0)
          (.trim)))

(defn- forwarded-host
  "Reads the first forwarded host value from request headers."
  [request]
  (some-> (common/header-value (:headers request) :x-forwarded-host)
          (.split ",")
          (aget 0)
          (.trim)))

(defn- forwarded-for
  "Reads the first forwarded client address from request headers."
  [request]
  (some-> (common/header-value (:headers request) :x-forwarded-for)
          (.split ",")
          (aget 0)
          (.trim)))

(defn proxy-headers-request
  "Associates forwarded connection details onto the request map."
  [request]
  (cond-> request
    (forwarded-proto request)
    (assoc :scheme (keyword (forwarded-proto request)))

    (forwarded-host request)
    (assoc :server-name (forwarded-host request))

    (forwarded-for request)
    (assoc :remote-addr (forwarded-for request))))

(defn wrap-forwarded-headers
  "Wraps a handler so forwarding headers update request connection fields."
  [handler]
  (common/wrap-request handler proxy-headers-request))
