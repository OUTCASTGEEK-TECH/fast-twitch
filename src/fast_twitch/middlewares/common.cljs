(ns fast-twitch.middlewares.common
  "Shared helpers for header handling, request conversion, and middleware composition."
  [:require [clojure.string :as str]]
  [:refer-global :only [Headers Promise Request URL]])

(defn promise?
  "Returns true when x behaves like a JavaScript promise."
  [x]
  (and (some? x) (fn? (aget x "then"))))

(defn promise
  "Wraps x in a resolved JavaScript promise."
  [x]
  (Promise.resolve x))

(defn header-key
  "Normalizes a header name to a lowercase keyword."
  [k]
  (-> k name str/lower-case keyword))

(defn header-value
  "Looks up a header value without caring about header name casing."
  [headers k]
  (let [lk (header-key k)
        ln (name lk)]
    (some (fn [[hk hv]]
            (when (= ln (-> hk name str/lower-case))
              hv))
          headers)))

(defn has-header?
  "Returns true when the given header is present."
  [headers k]
  (some? (header-value headers k)))

(defn assoc-header
  "Associates a header on a response map."
  [response k v]
  (assoc-in response [:headers k] v))

(defn append-header
  "Appends a header value while preserving any existing header entries."
  [headers k v]
  (let [current (some (fn [[hk hv]]
                        (when (= (name (header-key hk)) (name (header-key k)))
                          [hk hv]))
                      headers)]
    (if-let [[hk hv] current]
      (assoc headers hk (if (vector? hv) (conj hv v) [hv v]))
      (assoc headers k v))))

(defn remove-headers
  "Removes all headers whose names match the supplied collection."
  [headers names]
  (let [names (set (map header-key names))]
    (into {}
          (remove (fn [[k _]]
                    (contains? names (header-key k))))
          headers)))

(defn headers->entries
  "Converts a header map into name/value entry pairs for Fetch APIs."
  [headers]
  (map (fn [[k v]] [(name k) v]) headers))

(defn headers->map
  "Converts a Fetch Headers instance into a plain Clojure map."
  [headers]
  (into {}
        (map (fn [entry]
               [(aget entry 0) (aget entry 1)]))
        (.entries headers)))

(defn request-url
  "Builds a full request URL string from a request map."
  [request]
  (str (name (:scheme request))
       "://"
       (:server-name request)
       (when (:server-port request)
         (str ":" (:server-port request)))
       (:uri request)
       (when-let [query-string (:query-string request)]
         (str "?" query-string))))

(defn ft->fetch-request
  "Converts a request map into a Fetch Request instance."
  [request]
  (Request.
   (request-url request)
   (clj->js
    (cond-> {:method (-> request :request-method name str/upper-case)
             :headers (headers->entries (:headers request))}
      (:body request)
      (assoc :body (:body request)
             :duplex "half")))))

(defn fetch-response->ft
  "Converts a Fetch Response instance into a response map."
  [response]
  {:status (aget response "status")
   :headers (headers->map (aget response "headers"))
   :body (aget response "body")})

(defn wrap-request
  "Wraps a handler with a request transformation that may be asynchronous."
  [handler request-fn]
  (fn
    ([request]
     (let [request* (request-fn request)]
       (if (promise? request*)
         (.then request* handler)
         (handler request*))))
    ([request respond raise]
     (-> (promise (request-fn request))
         (.then (fn [request*]
                  (handler request* respond raise)))
         (.catch raise)))))

(defn wrap-response
  "Wraps a handler with a response transformation that sees the original request."
  [handler response-fn]
  (fn
    ([request]
     (let [response (handler request)]
       (if (promise? response)
         (.then response #(response-fn % request))
         (response-fn response request))))
    ([request respond raise]
     (handler request
              #(respond (response-fn % request))
              raise))))

(defn wrap-request-response
  "Wraps a handler with coordinated request and response transformations."
  [handler request-fn response-fn]
  (fn
    ([request]
     (let [request* (request-fn request)
           invoke (fn [request*]
                    (let [response (handler request*)]
                      (if (promise? response)
                        (.then response #(response-fn % request*))
                        (response-fn response request*))))]
       (if (promise? request*)
         (.then request* invoke)
         (invoke request*))))
    ([request respond raise]
     (-> (promise (request-fn request))
         (.then (fn [request*]
                  (handler request*
                           #(respond (response-fn % request*))
                           raise)))
         (.catch raise)))))
