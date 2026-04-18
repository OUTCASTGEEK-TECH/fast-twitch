(ns fast-twitch.middlewares.anti-forgery
  "Adds request token validation and token persistence for unsafe form submissions."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]])

(def ^:dynamic *anti-forgery-token*
  "The anti-forgery token bound while rendering a protected request."
  nil)

(def ^:dynamic *anti-forgery-param-name*
  "The anti-forgery form parameter name bound while rendering a protected request."
  nil)

(def unsafe-methods
  "HTTP methods that require anti-forgery validation by default."
  #{:post :put :patch :delete})

(def default-error-response
  "The default response returned when anti-forgery validation fails."
  {:status 403
   :headers {"Content-Type" "text/plain"}
   :body "Invalid anti-forgery token\n"})

(def default-token-param-name
  "The default form parameter name for submitted anti-forgery tokens."
  "__anti-forgery-token")

(def default-header-names
  "The default request headers checked for submitted anti-forgery tokens."
  ["X-CSRF-Token" "X-XSRF-Token"])

(def default-exempt-prefixes
  "The default URI prefixes exempted from anti-forgery checks."
  ["/static/" "/assets/" "/favicon" "/.well-known/"])

(defn- safe-eq?
  "Compares two values in constant time after coercing them to strings."
  [a b]
  (let [a (str a)
        b (str b)]
    (and (= (count a) (count b))
         (zero?
          (reduce bit-or
                  (map (fn [idx]
                         (bit-xor (.charCodeAt a idx)
                                  (.charCodeAt b idx)))
                       (range (count a))))))))

(defn- session-token
  "Returns the stored anti-forgery token or creates a new one for the request."
  [request token-generator]
  (or (get-in request [:session :anti-forgery-token])
      (token-generator)))

(defn- first-param-token
  "Returns the first submitted token found in the configured parameter names."
  [request param-names]
  (some (fn [param-name]
          (or (get-in request [:params (keyword param-name)])
              (get-in request [:params param-name])
              (get-in request [:form-params (keyword param-name)])
              (get-in request [:form-params param-name])
              (get-in request [:multipart-params (keyword param-name)])
              (get-in request [:multipart-params param-name])))
        param-names))

(defn- first-header-token
  "Returns the first submitted token found in the configured header names."
  [request header-names]
  (some #(common/header-value (:headers request) %) header-names))

(defn request-token
  "Reads a submitted anti-forgery token from params or request headers."
  ([request]
   (request-token request {}))
  ([request options]
   (let [param-names (or (:param-names options)
                         [(:param-name options default-token-param-name)])
         header-names (or (:header-names options) default-header-names)]
     (or (first-param-token request param-names)
         (first-header-token request header-names)))))

(defn token-param-name
  "Returns the configured token parameter name, falling back to the default."
  [options]
  (or (:param-name options)
      (first (:param-names options))
      default-token-param-name))

(defn- add-session-token
  "Stores the active token in the outgoing session when a session is available."
  [response request token]
  (if (and (contains? response :session)
           (nil? (:session response)))
    response
    (assoc response
           :session
           (assoc (or (:session response) (:session request) {})
                  :anti-forgery-token token))))

(defn- invalid-response
  "Builds the response returned when token validation fails."
  [request failure options]
  (when-let [logger (:logger options)]
    (logger (assoc failure
                   :event :anti-forgery/invalid
                   :uri (:uri request)
                   :request-method (:request-method request)
                   :request-id (:request-id request))))
  (when-let [on-invalid (:on-invalid options)]
    (on-invalid request failure))
  (if-let [handler (:error-handler options)]
    (handler request failure)
    (or (:error-response options) default-error-response)))

(defn- safe-header?
  "Checks whether any trusted header exempts the request from token comparison."
  [request safe-headers]
  (some #(common/header-value (:headers request) %) safe-headers))

(defn- exempt-path?
  "Returns true when a request URI is exempt from anti-forgery checks."
  [request options]
  (let [uri (:uri request)
        exempt? (:exempt? options)
        prefixes (or (:exempt-prefixes options) default-exempt-prefixes)]
    (or (and exempt? (exempt? request))
        (some #(str/starts-with? uri %) prefixes))))

(defn- valid-request?
  "Returns true when the request method is safe, exempt, trusted, or token-valid."
  [request token read-token safe-headers options]
  (or (not (contains? unsafe-methods (:request-method request)))
      (exempt-path? request options)
      (safe-header? request safe-headers)
      (safe-eq? token (read-token request options))))

(defn wrap-anti-forgery
  "Wraps a handler with anti-forgery token validation and session token storage."
  ([handler]
   (wrap-anti-forgery handler {}))
  ([handler options]
   (let [read-token (or (:read-token options) request-token)
         safe-headers (cond
                        (:safe-headers options) (:safe-headers options)
                        (:safe-header options) [(:safe-header options)]
                        :else [])
         param-name (token-param-name options)
         token-generator (or (:token-generator options) #(str (random-uuid)))]
     (fn
       ([request]
        (let [token (session-token request token-generator)
              request (assoc request :anti-forgery-token token)]
          (binding [*anti-forgery-token* token
                    *anti-forgery-param-name* param-name]
            (if (valid-request? request token read-token safe-headers options)
              (let [response (handler request)]
                (if (common/promise? response)
                  (.then response #(add-session-token % request token))
                  (add-session-token response request token)))
              (invalid-response request {:reason :invalid-token} options)))))
       ([request respond raise]
        (let [token (session-token request token-generator)
              request (assoc request :anti-forgery-token token)]
          (binding [*anti-forgery-token* token
                    *anti-forgery-param-name* param-name]
            (if (valid-request? request token read-token safe-headers options)
              (handler request
                       #(respond (add-session-token % request token))
                       raise)
              (respond (invalid-response request
                                         {:reason :invalid-token}
                                         options))))))))))
