(ns ddc.middlewares.cookies
  [:require
   [clojure.string :as str]
   [ddc.middlewares.common :as common]]
  [:refer-global :only [Date decodeURIComponent encodeURIComponent]])

(defn- safe-decode [s]
  (try
    (decodeURIComponent s)
    (catch :default _
      s)))

(defn- safe-encode [s]
  (encodeURIComponent (str s)))

(defn- cookie-pair [part]
  (let [idx (.indexOf part "=")]
    (when (pos? idx)
      [(keyword (str/trim (subs part 0 idx)))
       {:value (safe-decode (subs part (inc idx)))}])))

(defn- parse-cookies [cookie-header]
  (into {}
        (keep cookie-pair)
        (str/split (or cookie-header "") ";")))

(defn cookies-request
  ([request]
   (cookies-request request {}))
  ([request _options]
   (assoc request :cookies (parse-cookies (get-in request [:headers :cookie])))))

(defn- same-site-value [value]
  (case value
    :strict "Strict"
    :lax "Lax"
    :none "None"
    value))

(defn- expires [value]
  (cond
    (nil? value) nil
    (string? value) value
    (number? value) (.toUTCString (Date. value))
    :else (.toUTCString value)))

(defn- cookie-string [cookie-name cookie]
  (let [cookie (if (map? cookie) cookie {:value cookie})
        attrs [(str (safe-encode (name cookie-name)) "=" (safe-encode (:value cookie)))
               (when-let [path (:path cookie)] (str "Path=" path))
               (when-let [domain (:domain cookie)] (str "Domain=" domain))
               (when-let [max-age (:max-age cookie)] (str "Max-Age=" max-age))
               (when-let [expires (expires (:expires cookie))] (str "Expires=" expires))
               (when (:secure cookie) "Secure")
               (when (:http-only cookie) "HttpOnly")
               (when-let [same-site (:same-site cookie)] (str "SameSite=" (same-site-value same-site)))]]
    (str/join "; " (remove nil? attrs))))

(defn cookies-response
  ([response]
   (cookies-response response {}))
  ([response _options]
   (if-let [cookies (:cookies response)]
     (let [headers (reduce (fn [headers [name cookie]]
                             (common/append-header headers "Set-Cookie" (cookie-string name cookie)))
                           (:headers response)
                           cookies)]
       (-> response
           (assoc :headers headers)
           (dissoc :cookies)))
     response)))

(defn wrap-cookies
  ([handler]
   (wrap-cookies handler {}))
  ([handler options]
   (common/wrap-request-response
    handler
    #(cookies-request % options)
    (fn [response _request]
      (cookies-response response options)))))
