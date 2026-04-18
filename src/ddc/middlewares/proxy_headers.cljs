(ns ddc.middlewares.proxy-headers
  [:require [ddc.middlewares.common :as common]])

(defn- forwarded-proto [request]
  (some-> (common/header-value (:headers request) :x-forwarded-proto)
          (.split ",")
          (aget 0)
          (.trim)))

(defn- forwarded-host [request]
  (some-> (common/header-value (:headers request) :x-forwarded-host)
          (.split ",")
          (aget 0)
          (.trim)))

(defn- forwarded-for [request]
  (some-> (common/header-value (:headers request) :x-forwarded-for)
          (.split ",")
          (aget 0)
          (.trim)))

(defn proxy-headers-request [request]
  (cond-> request
    (forwarded-proto request)
    (assoc :scheme (keyword (forwarded-proto request)))

    (forwarded-host request)
    (assoc :server-name (forwarded-host request))

    (forwarded-for request)
    (assoc :remote-addr (forwarded-for request))))

(defn wrap-forwarded-headers [handler]
  (common/wrap-request handler proxy-headers-request))
