(ns ddc.middlewares.anti-forgery
  [:require [ddc.middlewares.common :as common]])

(def ^:dynamic *anti-forgery-token* nil)

(def unsafe-methods #{:post :put :patch :delete})

(def default-error-response
  {:status 403
   :headers {"Content-Type" "text/plain"}
   :body "Invalid anti-forgery token\n"})

(defn- safe-eq? [a b]
  (let [a (str a)
        b (str b)]
    (and (= (count a) (count b))
         (zero?
          (reduce bit-or
                  (map (fn [idx]
                         (bit-xor (.charCodeAt a idx)
                                  (.charCodeAt b idx)))
                       (range (count a))))))))

(defn- session-token [request]
  (or (get-in request [:session :anti-forgery-token])
      (str (random-uuid))))

(defn- request-token [request]
  (or (get-in request [:params :__anti-forgery-token])
      (get-in request [:params "__anti-forgery-token"])
      (get-in request [:form-params :__anti-forgery-token])
      (get-in request [:form-params "__anti-forgery-token"])
      (get-in request [:headers :x-csrf-token])
      (get-in request [:headers :x-xsrf-token])))

(defn- add-session-token [response request token]
  (if (and (contains? response :session)
           (nil? (:session response)))
    response
    (assoc response
           :session
           (assoc (or (:session response) (:session request) {})
                  :anti-forgery-token token))))

(defn- invalid-response [request options]
  (if-let [handler (:error-handler options)]
    (handler request)
    (or (:error-response options) default-error-response)))

(defn- safe-header? [request safe-header]
  (and safe-header
       (get-in request [:headers (keyword (.toLowerCase safe-header))])))

(defn- valid-request? [request token read-token safe-header]
  (or (not (contains? unsafe-methods (:request-method request)))
      (safe-header? request safe-header)
      (safe-eq? token (read-token request))))

(defn wrap-anti-forgery
  ([handler]
   (wrap-anti-forgery handler {}))
  ([handler options]
   (let [read-token (or (:read-token options) request-token)
         safe-header (:safe-header options)]
     (fn
       ([request]
        (let [token (session-token request)
              request (assoc request :anti-forgery-token token)]
          (binding [*anti-forgery-token* token]
            (if (valid-request? request token read-token safe-header)
              (let [response (handler request)]
                (if (common/promise? response)
                  (.then response #(add-session-token % request token))
                  (add-session-token response request token)))
              (invalid-response request options)))))
       ([request respond raise]
        (let [token (session-token request)
              request (assoc request :anti-forgery-token token)]
          (binding [*anti-forgery-token* token]
            (if (valid-request? request token read-token safe-header)
              (handler request
                       #(respond (add-session-token % request token))
                       raise)
              (respond (invalid-response request options))))))))))
