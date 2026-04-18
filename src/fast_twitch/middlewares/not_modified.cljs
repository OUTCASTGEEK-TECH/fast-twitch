(ns fast-twitch.middlewares.not-modified
  "Short-circuits cacheable responses when validators show the resource is unchanged."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Date]])

(defn- etag-match?
  "Checks whether an If-None-Match header matches the current entity tag."
  [if-none-match etag]
  (let [candidates (map str/trim (str/split (or if-none-match "") ","))]
    (or (some #{"*"} candidates)
        (some #{etag} candidates))))

(defn- date-ms
  "Parses an HTTP date into milliseconds since epoch when valid."
  [s]
  (let [ms (Date.parse s)]
    (when-not (js/isNaN ms)
      ms)))

(defn- modified-since?
  "Returns true when the cached timestamp is at least as new as the response timestamp."
  [if-modified-since last-modified]
  (when-let [request-ms (date-ms if-modified-since)]
    (when-let [response-ms (date-ms last-modified)]
      (>= request-ms response-ms))))

(defn- not-modified?
  "Determines whether request validators allow a 304 Not Modified response."
  [response request]
  (let [headers (:headers response)
        request-headers (:headers request)
        etag (common/header-value headers :etag)
        last-modified (common/header-value headers :last-modified)
        if-none-match (common/header-value request-headers :if-none-match)
        if-modified-since (common/header-value request-headers :if-modified-since)]
    (or (and etag if-none-match (etag-match? if-none-match etag))
        (and last-modified if-modified-since
             (modified-since? if-modified-since last-modified)))))

(defn not-modified-response
  "Replaces a cache hit response with a 304 response for GET and HEAD requests."
  [response request]
  (if (and (#{:get :head} (:request-method request))
           (not-modified? response request))
    (-> response
        (assoc :status 304 :body nil)
        (update :headers common/remove-headers
                [:content-type :content-length]))
    response))

(defn wrap-not-modified
  "Wraps a handler so conditional requests can return 304 responses."
  [handler]
  (common/wrap-response handler not-modified-response))
