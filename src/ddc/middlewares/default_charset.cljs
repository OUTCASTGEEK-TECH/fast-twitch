(ns ddc.middlewares.default-charset
  [:require
   [clojure.string :as str]
   [ddc.middlewares.common :as common]])

(defn- needs-charset? [content-type]
  (and content-type
       (str/starts-with? (str/lower-case content-type) "text/")
       (not (str/includes? (str/lower-case content-type) "charset="))))

(defn default-charset-response
  ([response request]
   (default-charset-response response request "utf-8"))
  ([response _request charset]
   (let [content-type (common/header-value (:headers response) :content-type)]
     (if (needs-charset? content-type)
       (common/assoc-header response "Content-Type" (str content-type "; charset=" charset))
       response))))

(defn wrap-default-charset
  ([handler]
   (wrap-default-charset handler "utf-8"))
  ([handler charset]
   (common/wrap-response handler #(default-charset-response %1 %2 charset))))
