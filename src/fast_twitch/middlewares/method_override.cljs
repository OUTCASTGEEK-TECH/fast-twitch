(ns fast-twitch.middlewares.method-override
  "Overrides request methods for clients that can only submit POST requests."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]])

(def default-header-name
  "The default header used to tunnel an HTTP method."
  "X-HTTP-Method-Override")

(def default-param-name
  "The default parameter used to tunnel an HTTP method."
  "_method")

(def default-allowed-methods
  "The default set of methods that may be applied by method override."
  #{:put :patch :delete})

(defn- normalized-method
  "Normalizes a method value into a lowercase keyword."
  [method]
  (when (seq (str/trim (str method)))
    (-> method str str/trim str/lower-case keyword)))

(defn- submitted-method
  "Reads the submitted override method from headers or parsed params."
  [request header-name param-name]
  (or (common/header-value (:headers request) header-name)
      (get-in request [:params (keyword param-name)])
      (get-in request [:params param-name])
      (get-in request [:form-params (keyword param-name)])
      (get-in request [:form-params param-name])
      (get-in request [:multipart-params (keyword param-name)])
      (get-in request [:multipart-params param-name])))

(defn method-override-request
  "Updates :request-method when a POST request submits an allowed override."
  ([request]
   (method-override-request request {}))
  ([request options]
   (let [header-name (or (:header-name options) default-header-name)
         param-name (or (:param-name options) default-param-name)
         allowed-methods (or (:allowed-methods options) default-allowed-methods)
         method (normalized-method (submitted-method request header-name param-name))]
     (if (and (= :post (:request-method request))
              (contains? allowed-methods method))
       (assoc request
              :original-request-method (:request-method request)
              :request-method method)
       request))))

(defn wrap-method-override
  "Wraps a handler so POST requests can opt into PUT, PATCH, or DELETE handling."
  ([handler]
   (wrap-method-override handler {}))
  ([handler options]
   (common/wrap-request handler #(method-override-request % options))))
