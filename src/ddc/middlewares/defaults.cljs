(ns ddc.middlewares.defaults
  [:require
   [ddc.middlewares.absolute-redirects :as absolute-redirects]
   [ddc.middlewares.anti-forgery :as anti-forgery]
   [ddc.middlewares.content-length :as content-length]
   [ddc.middlewares.content-type :as content-type]
   [ddc.middlewares.cookies :as cookies]
   [ddc.middlewares.default-charset :as default-charset]
   [ddc.middlewares.flash :as flash]
   [ddc.middlewares.head :as head]
   [ddc.middlewares.keyword-params :as keyword-params]
   [ddc.middlewares.multipart-params :as multipart-params]
   [ddc.middlewares.nested-params :as nested-params]
   [ddc.middlewares.not-modified :as not-modified]
   [ddc.middlewares.params :as params]
   [ddc.middlewares.proxy-headers :as proxy-headers]
   [ddc.middlewares.session :as session]
   [ddc.middlewares.ssl :as ssl]
   [ddc.middlewares.x-headers :as x-headers]])

(def api-defaults
  {:params {:urlencoded true
            :keywordize true}
   :responses {:not-modified-responses true
               :absolute-redirects false
               :content-length true
               :content-types true
               :default-charset "utf-8"}
   :security {:x-content-type-options "nosniff"
              :x-frame-options :sameorigin
              :x-xss-protection "0"
              :ssl-redirect false
              :hsts false
              :absolute-redirects false
              :proxy-headers false}})

(def site-defaults
  (-> api-defaults
      (assoc :cookies true)
      (assoc :session {:cookie-name "ring-session"
                       :cookie-attrs {:http-only true
                                      :same-site :lax}
                       :flash true})
      (assoc-in [:params :multipart] true)
      (assoc-in [:params :nested] true)
      (assoc-in [:security :anti-forgery]
                {:safe-header "X-Ring-Anti-Forgery"})))

(defn- truthy-options [options]
  (cond
    (true? options) {}
    (map? options) options
    :else nil))

(defn- wrap-params-defaults [handler options]
  (cond-> handler
    (get-in options [:params :keywordize])
    (keyword-params/wrap-keyword-params)

    (get-in options [:params :nested])
    (nested-params/wrap-nested-params)

    (get-in options [:params :multipart])
    (multipart-params/wrap-multipart-params)

    (get-in options [:params :urlencoded])
    (params/wrap-params)))

(defn- wrap-response-defaults [handler options]
  (cond-> handler
    (get-in options [:responses :default-charset])
    (default-charset/wrap-default-charset
     (get-in options [:responses :default-charset]))

    (get-in options [:responses :content-length])
    (content-length/wrap-content-length)

    (get-in options [:responses :content-types])
    (content-type/wrap-content-type)

    (get-in options [:responses :not-modified-responses])
    (not-modified/wrap-not-modified)

    true
    (head/wrap-head)))

(defn- wrap-security-defaults [handler options]
  (let [security (:security options)]
    (cond-> handler
      (:anti-forgery security)
      (anti-forgery/wrap-anti-forgery
       (truthy-options (:anti-forgery security)))

      (:absolute-redirects security)
      (absolute-redirects/wrap-absolute-redirects)

      (or (:ssl-redirect security) (:hsts security))
      (ssl/wrap-ssl {:ssl-redirect? (:ssl-redirect security)
                     :hsts? (:hsts security)
                     :hsts (truthy-options (:hsts security))})

      (:proxy-headers security)
      (proxy-headers/wrap-forwarded-headers)

      (or (:x-content-type-options security)
          (:x-frame-options security)
          (:x-xss-protection security))
      (x-headers/wrap-x-headers
       {:content-type-options (:x-content-type-options security)
        :frame-options (:x-frame-options security)
        :xss-protection (:x-xss-protection security)}))))

(defn wrap-defaults [handler options]
  (cond-> handler
    true
    (wrap-security-defaults options)

    (get-in options [:session :flash])
    (flash/wrap-flash)

    (:session options)
    (session/wrap-session (:session options))

    true
    (wrap-params-defaults options)

    (:cookies options)
    (cookies/wrap-cookies)

    true
    (wrap-response-defaults options)))
