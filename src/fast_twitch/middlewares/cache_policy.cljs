(ns fast-twitch.middlewares.cache-policy
  "Applies configurable HTTP cache policy headers with @std/cache-backed memoization."
  [:require
   [cljs.nodejs :as nodejs]
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]])

(def LruCache
  "The @std/cache LruCache constructor used by the default policy backend."
  (aget (nodejs/require "@std/cache") "LruCache"))

(def TtlCache
  "The @std/cache TtlCache constructor available for custom policy backends."
  (aget (nodejs/require "@std/cache") "TtlCache"))

(def default-static-prefixes
  "The default URI prefixes treated as static cacheable assets."
  ["/static/" "/assets/" "/favicon"])

(def default-dynamic-policy
  "The default cache policy for non-static responses."
  {"Cache-Control" "no-cache, no-store, must-revalidate"
   "Pragma" "no-cache"
   "Expires" "0"})

(defn lru-cache-backend
  "Creates an @std/cache LRU backend for memoized cache policy headers."
  ([]
   (lru-cache-backend 256))
  ([max-size]
   (LruCache. max-size)))

(defn ttl-cache-backend
  "Creates an @std/cache TTL backend for memoized cache policy headers."
  [ttl-ms]
  (TtlCache. ttl-ms))

(defn- path-matches-prefix?
  "Returns true when path starts with one of the supplied prefixes."
  [path prefixes]
  (some #(str/starts-with? path %) prefixes))

(defn cacheable-request?
  "Returns true when the request should receive a static cache policy."
  ([request]
   (cacheable-request? request {}))
  ([request options]
   (and (#{:get :head} (:request-method request))
        (path-matches-prefix? (:uri request)
                              (or (:static-prefixes options)
                                  default-static-prefixes)))))

(defn cache-policy-key
  "Builds the backend key used for memoizing cache policy headers."
  [request options]
  (str (:request-method request)
       " "
       (:uri request)
       " "
       (if ((or (:cacheable? options) cacheable-request?) request options)
         "static"
         "dynamic")))

(defn static-cache-policy
  "Builds the cache policy used for static cacheable assets."
  [request options]
  (let [max-age (or (:max-age options) 31536000)
        immutable? (not (false? (:immutable? options)))
        etag-fn (:etag-fn options)]
    (cond-> {"Cache-Control" (str "public, max-age=" max-age
                                  (when immutable?
                                    ", immutable"))}
      etag-fn
      (assoc "ETag" (etag-fn request)))))

(defn dynamic-cache-policy
  "Builds the cache policy used for non-static responses."
  [_request options]
  (or (:dynamic-policy options) default-dynamic-policy))

(defn- policy-value
  "Returns a policy map from either a policy map or a policy function."
  [policy request options fallback]
  (cond
    (fn? policy) (policy request options)
    (map? policy) policy
    :else fallback))

(defn cache-policy
  "Returns cache policy headers for the request, using the configured backend."
  [request options]
  (let [backend (or (:backend options) (lru-cache-backend))
        key ((or (:key-fn options) cache-policy-key) request options)
        cached (.get backend key)]
    (if cached
      cached
      (let [policy (if ((or (:cacheable? options) cacheable-request?) request options)
                     (policy-value (:static-policy options)
                                   request
                                   options
                                   (static-cache-policy request options))
                     (policy-value (:dynamic-policy options)
                                   request
                                   options
                                   (dynamic-cache-policy request options)))]
        (.set backend key policy)
        policy))))

(defn cache-policy-response
  "Adds cache policy headers to a response without replacing existing headers."
  [response request options]
  (reduce (fn [response [k v]]
            (if (common/has-header? (:headers response) k)
              response
              (common/assoc-header response k v)))
          response
          (cache-policy request options)))

(defn wrap-cache-policy
  "Wraps a handler so responses receive configurable cache policy headers."
  ([handler]
   (wrap-cache-policy handler {}))
  ([handler options]
   (let [backend (or (:backend options)
                     (lru-cache-backend (or (:max-entries options) 256)))
         options (assoc options :backend backend)]
     (common/wrap-response handler #(cache-policy-response %1 %2 options)))))
