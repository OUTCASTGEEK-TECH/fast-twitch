(ns fast-twitch.middlewares.default-charset
  "Appends a charset parameter to text-based responses that do not already declare one."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]])

(defn- needs-charset?
  "Returns true when a text content type is missing a charset parameter."
  [content-type]
  (and content-type
       (str/starts-with? (str/lower-case content-type) "text/")
       (not (str/includes? (str/lower-case content-type) "charset="))))

(defn default-charset-response
  "Adds a default charset to eligible responses."
  ([response request]
   (default-charset-response response request "utf-8"))
  ([response _request charset]
   (let [content-type (common/header-value (:headers response) :content-type)]
     (if (needs-charset? content-type)
       (common/assoc-header response "Content-Type" (str content-type "; charset=" charset))
       response))))

(defn wrap-default-charset
  "Wraps a handler so text responses receive a fallback charset."
  ([handler]
   (wrap-default-charset handler "utf-8"))
  ([handler charset]
   (common/wrap-response handler #(default-charset-response %1 %2 charset))))
