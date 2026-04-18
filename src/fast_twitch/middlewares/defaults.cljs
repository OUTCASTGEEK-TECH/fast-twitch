(ns fast-twitch.middlewares.defaults
  "Preconfigured middleware bundles for common API and site-oriented applications."
  [:require
   [fast-twitch.middlewares.absolute-redirects :as absolute-redirects]
   [fast-twitch.middlewares.anti-forgery :as anti-forgery]
   [fast-twitch.middlewares.content-length :as content-length]
   [fast-twitch.middlewares.content-type :as content-type]
   [fast-twitch.middlewares.cookies :as cookies]
   [fast-twitch.middlewares.default-charset :as default-charset]
   [fast-twitch.middlewares.flash :as flash]
   [fast-twitch.middlewares.head :as head]
   [fast-twitch.middlewares.keyword-params :as keyword-params]
   [fast-twitch.middlewares.multipart-params :as multipart-params]
   [fast-twitch.middlewares.nested-params :as nested-params]
   [fast-twitch.middlewares.not-modified :as not-modified]
   [fast-twitch.middlewares.params :as params]
   [fast-twitch.middlewares.proxy-headers :as proxy-headers]
   [fast-twitch.middlewares.session :as session]
   [fast-twitch.middlewares.ssl :as ssl]
   [fast-twitch.middlewares.x-headers :as x-headers]])

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
      (assoc :session {:cookie-name "ft-session"
                       :cookie-attrs {:http-only true
                                      :same-site :lax}
                       :flash true})
      (assoc-in [:params :multipart] true)
      (assoc-in [:params :nested] true)
      (assoc-in [:security :anti-forgery]
                {:safe-header "X-Ft-Anti-Forgery"})))

(defn- truthy-options
  "Normalizes boolean-or-map options into either a map or nil."
  [options]
  (cond
    (true? options) {}
    (map? options) options
    :else nil))

(defn- wrap-params-defaults
  "Applies the configured parameter parsing middleware stack."
  [handler options]
  (cond-> handler
    (get-in options [:params :keywordize])
    (keyword-params/wrap-keyword-params)

    (get-in options [:params :nested])
    (nested-params/wrap-nested-params)

    (get-in options [:params :multipart])
    (multipart-params/wrap-multipart-params)

    (get-in options [:params :urlencoded])
    (params/wrap-params)))

(defn- wrap-response-defaults
  "Applies the configured response middleware stack."
  [handler options]
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

(defn- wrap-security-defaults
  "Applies the configured security and deployment-related middleware stack."
  [handler options]
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

(defn wrap-defaults
  "Applies the configured default middleware bundles to a handler."
  [handler options]
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
