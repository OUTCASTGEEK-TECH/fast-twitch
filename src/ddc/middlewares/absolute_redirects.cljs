(ns ddc.middlewares.absolute-redirects
  [:require
   [clojure.string :as str]
   [ddc.middlewares.common :as common]]
  [:refer-global :only [URL]])

(def redirect-statuses #{301 302 303 307 308})

(defn- absolute? [location]
  (or (str/starts-with? location "http://")
      (str/starts-with? location "https://")
      (str/starts-with? location "//")))

(defn- request-origin [request]
  (str (name (:scheme request))
       "://"
       (:server-name request)
       (when (:server-port request)
         (str ":" (:server-port request)))))

(defn- absolute-location [location request]
  (if (absolute? location)
    location
    (str (URL. location (request-origin request)))))

(defn absolute-redirects-response [response request]
  (if (contains? redirect-statuses (:status response))
    (if-let [location (common/header-value (:headers response) :location)]
      (common/assoc-header response "Location" (absolute-location location request))
      response)
    response))

(defn wrap-absolute-redirects [handler]
  (common/wrap-response handler absolute-redirects-response))
