(ns fast-twitch.middlewares.absolute-redirects
  "Rewrites redirect targets so clients receive fully qualified locations when needed."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [URL]])

(def redirect-statuses #{301 302 303 307 308})

(defn- absolute?
  "Returns true when a location string already contains an absolute target."
  [location]
  (or (str/starts-with? location "http://")
      (str/starts-with? location "https://")
      (str/starts-with? location "//")))

(defn- request-origin
  "Builds the origin portion of a request from scheme, host, and port fields."
  [request]
  (str (name (:scheme request))
       "://"
       (:server-name request)
       (when (:server-port request)
         (str ":" (:server-port request)))))

(defn- absolute-location
  "Converts a relative redirect target into an absolute URL for the current request."
  [location request]
  (if (absolute? location)
    location
    (str (URL. location (request-origin request)))))

(defn absolute-redirects-response
  "Normalizes redirect responses so their Location header is absolute."
  [response request]
  (if (contains? redirect-statuses (:status response))
    (if-let [location (common/header-value (:headers response) :location)]
      (common/assoc-header response "Location" (absolute-location location request))
      response)
    response))

(defn wrap-absolute-redirects
  "Wraps a handler so redirect responses carry absolute Location headers."
  [handler]
  (common/wrap-response handler absolute-redirects-response))
