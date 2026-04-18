(ns fast-twitch.middlewares.common
  [:require [clojure.string :as str]]
  [:refer-global :only [Headers Promise Request URL]])

(defn promise? [x]
  (and (some? x) (fn? (aget x "then"))))

(defn promise [x]
  (Promise.resolve x))

(defn header-key [k]
  (-> k name str/lower-case keyword))

(defn header-value [headers k]
  (let [lk (header-key k)
        ln (name lk)]
    (some (fn [[hk hv]]
            (when (= ln (-> hk name str/lower-case))
              hv))
          headers)))

(defn has-header? [headers k]
  (some? (header-value headers k)))

(defn assoc-header [response k v]
  (assoc-in response [:headers k] v))

(defn append-header [headers k v]
  (let [current (some (fn [[hk hv]]
                        (when (= (name (header-key hk)) (name (header-key k)))
                          [hk hv]))
                      headers)]
    (if-let [[hk hv] current]
      (assoc headers hk (if (vector? hv) (conj hv v) [hv v]))
      (assoc headers k v))))

(defn remove-headers [headers names]
  (let [names (set (map header-key names))]
    (into {}
          (remove (fn [[k _]]
                    (contains? names (header-key k))))
          headers)))

(defn headers->entries [headers]
  (map (fn [[k v]] [(name k) v]) headers))

(defn headers->map [headers]
  (into {}
        (map (fn [entry]
               [(aget entry 0) (aget entry 1)]))
        (.entries headers)))

(defn request-url [request]
  (str (name (:scheme request))
       "://"
       (:server-name request)
       (when (:server-port request)
         (str ":" (:server-port request)))
       (:uri request)
       (when-let [query-string (:query-string request)]
         (str "?" query-string))))

(defn ft->fetch-request [request]
  (Request.
   (request-url request)
   (clj->js
    (cond-> {:method (-> request :request-method name str/upper-case)
             :headers (headers->entries (:headers request))}
      (:body request)
      (assoc :body (:body request)
             :duplex "half")))))

(defn fetch-response->ft [response]
  {:status (aget response "status")
   :headers (headers->map (aget response "headers"))
   :body (aget response "body")})

(defn wrap-request [handler request-fn]
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

(defn wrap-response [handler response-fn]
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

(defn wrap-request-response [handler request-fn response-fn]
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
