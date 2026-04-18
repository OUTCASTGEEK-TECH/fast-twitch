(ns fast-twitch.middlewares.cookies
  "Parses incoming cookies and serializes outgoing cookie instructions."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Date decodeURIComponent encodeURIComponent]])

(defn- safe-decode
  "Decodes a cookie value and falls back to the original string on failure."
  [s]
  (try
    (decodeURIComponent s)
    (catch :default _
      s)))

(defn- safe-encode
  "Encodes a cookie name or value for transport."
  [s]
  (encodeURIComponent (str s)))

(defn- cookie-pair
  "Parses one cookie pair from a Cookie header fragment."
  [part]
  (let [idx (.indexOf part "=")]
    (when (pos? idx)
      [(keyword (str/trim (subs part 0 idx)))
       {:value (safe-decode (subs part (inc idx)))}])))

(defn- parse-cookies
  "Parses a Cookie header string into the request cookie map format."
  [cookie-header]
  (into {}
        (keep cookie-pair)
        (str/split (or cookie-header "") ";")))

(defn cookies-request
  "Associates parsed cookies on the request map."
  ([request]
   (cookies-request request {}))
  ([request _options]
   (assoc request :cookies (parse-cookies (get-in request [:headers :cookie])))))

(defn- same-site-value
  "Converts keyword same-site settings into cookie attribute strings."
  [value]
  (case value
    :strict "Strict"
    :lax "Lax"
    :none "None"
    value))

(defn- expires
  "Formats cookie expiration values as UTC strings when needed."
  [value]
  (cond
    (nil? value) nil
    (string? value) value
    (number? value) (.toUTCString (Date. value))
    :else (.toUTCString value)))

(defn- cookie-string
  "Builds a Set-Cookie header value from the cookie map format."
  [cookie-name cookie]
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
  "Serializes response cookies into Set-Cookie headers and removes :cookies."
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
  "Wraps a handler with cookie parsing on the way in and serialization on the way out."
  ([handler]
   (wrap-cookies handler {}))
  ([handler options]
   (common/wrap-request-response
    handler
    #(cookies-request % options)
    (fn [response _request]
      (cookies-response response options)))))
