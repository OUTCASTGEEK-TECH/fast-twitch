(ns fast-twitch.middlewares.common
  "Shared helpers for header handling, request conversion, and middleware composition."
  [:require [cljs.core :refer [await]]
            [clojure.string :as str]
            [fast-twitch.util.http.request :as request]
            [fast-twitch.util.http.response :as response]
            [fast-twitch.util.async.settlement :as settlement]
            [fast-twitch.server.websocket :as upgrade]
            [fast-twitch.util.http.headers :as http-headers]]
  [:refer-global :only
                 [Headers Promise Request URL]])

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
  (-> k
      name
      str/lower-case
      keyword))

(defn header-value
  "Looks up a header value without caring about header name casing."
  [headers k]
  (let [lk (header-key k)
        ln (name lk)]
    (some (fn [[hk hv]]
            (when (= ln
                     (-> hk
                         name
                         str/lower-case))
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
                        (when (= (name (header-key hk)) (name (header-key k))) [hk hv]))
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
  (http-headers/ring-entries headers))

(defn headers->map
  "Converts a Fetch Headers instance into a plain Clojure map."
  [headers]
  (http-headers/headers->map headers))

(defn request-url
  "Builds a full request URL string from a Ring request map."
  [m]
  (request/request-url m :ring-map))

(defn ft->fetch-request
  [m]
  (request/map->request m {} :ring-map))

(defn fetch-response->ft
  [native]
  (response/response->map native :ring-map))

(defn- transformed-callbacks
  [respond raise transform]
  (let [settled? (atom false)]
    {:raise (fn [error]
              (if (compare-and-set! settled? false true)
                (raise error)
                (settlement/discard! error)))
     :respond (fn [value]
                (if (compare-and-set! settled? false true)
                  ((^:async fn
                    []
                    (try (await (respond (await (transform (await value)))))
                         (catch :default error (raise error)))))
                  (settlement/discard! value)))}))

(defn wrap-request
  "Wraps a handler with a request transformation that may be asynchronous."
  [handler request-fn]
  (fn
    ([request]
     (let [request* (request-fn request)]
       (if (promise? request*)
         ((^:async fn
           []
           (let [request* (await request*)]
             (upgrade/bind! request*)
             (handler request*))))
         (do (upgrade/bind! request*) (handler request*)))))
    ([request respond raise]
     ((^:async fn
       []
       (try
         (let [request* (await (request-fn request))
               {respond* :respond raise* :raise}
                 (transformed-callbacks respond raise identity)]
           (upgrade/bind! request*)
           (try (settlement/discard! (handler request* respond* raise*))
                (catch :default error (raise* error))))
         (catch :default error (raise error))))))))

(defn wrap-response
  "Wraps a handler with a response transformation that sees the original request."
  [handler response-fn]
  (fn
    ([request]
     (let [response (handler request)]
       (if (promise? response)
         ((^:async fn
           []
           (response-fn (response/normalize (await response)) request)))
         (response-fn (response/normalize response) request))))
    ([request respond raise]
     (let [{respond* :respond raise* :raise}
             (transformed-callbacks respond
                                    raise
                                    #(response-fn (response/normalize %) request))]
       (try (settlement/discard! (handler request respond* raise*))
            (catch :default error (raise* error)))))))

(defn wrap-request-response
  "Wraps a handler with coordinated request and response transformations."
  [handler request-fn response-fn]
  (wrap-request (wrap-response handler response-fn) request-fn))
