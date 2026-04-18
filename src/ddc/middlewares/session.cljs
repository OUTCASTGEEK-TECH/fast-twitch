(ns ddc.middlewares.session
  [:require
   [ddc.middlewares.cookies :as cookies]
   [ddc.middlewares.common :as common]])

(defonce default-store (atom {}))

(defn memory-store []
  (atom {}))

(defn read-session [store key]
  (get @store key))

(defn write-session [store key data]
  (let [key (or key (str (random-uuid)))]
    (swap! store assoc key data)
    key))

(defn delete-session [store key]
  (when key
    (swap! store dissoc key))
  nil)

(defn session-request
  ([request]
   (session-request request {}))
  ([request options]
   (let [request (if (:cookies request)
                   request
                   (cookies/cookies-request request))
         cookie-name (or (:cookie-name options) "ring-session")
         session-key (get-in request [:cookies (keyword cookie-name) :value])
         store (or (:store options) default-store)]
     (assoc request
            :session/key session-key
            :session (or (read-session store session-key) {})))))

(defn session-response
  ([response request]
   (session-response response request {}))
  ([response request options]
   (if (contains? response :session)
     (let [cookie-name (or (:cookie-name options) "ring-session")
           cookie-attrs (merge {:path (or (:root options) "/")
                                :http-only true}
                               (:cookie-attrs options)
                               (:session-cookie-attrs response))
           store (or (:store options) default-store)
           old-key (:session/key request)
           session (:session response)
           new-key (if (nil? session)
                     (delete-session store old-key)
                     (write-session store old-key session))
           cookie (if new-key
                    (assoc cookie-attrs :value new-key)
                    (assoc cookie-attrs :value "" :max-age 0))]
       (-> response
           (dissoc :session :session-cookie-attrs)
           (assoc-in [:cookies (keyword cookie-name)] cookie)))
     response)))

(defn wrap-session
  ([handler]
   (wrap-session handler {}))
  ([handler options]
   (common/wrap-request-response
    handler
    #(session-request % options)
    (fn [response request]
      (cookies/cookies-response
       (session-response response request options))))))
