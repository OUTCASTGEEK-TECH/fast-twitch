(ns fast-twitch.middlewares.proxy-headers
  "Adapts request connection details from forwarding and real-IP headers."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]])

(def default-real-ip-headers
  "The default ordered headers used to derive the real client IP."
  ["X-Forwarded-For" "X-Real-IP" "CF-Connecting-IP"])

(defn- first-header-part
  "Reads the first comma-delimited value from a request header."
  [request header-name]
  (some-> (common/header-value (:headers request) header-name)
          (.split ",")
          (aget 0)
          str/trim
          not-empty))

(defn- strip-quotes
  "Removes one pair of surrounding quotes from a forwarded header value."
  [value]
  (let [value (str/trim (str value))]
    (if (and (str/starts-with? value "\"")
             (str/ends-with? value "\""))
      (subs value 1 (dec (count value)))
      value)))

(defn- forwarded-param
  "Reads one parameter from an RFC Forwarded header value."
  [request pattern]
  (some-> (common/header-value (:headers request) "Forwarded")
          (as-> value (re-find pattern value))
          second
          strip-quotes
          str/trim
          not-empty))

(defn forwarded-proto
  "Reads the first forwarded protocol value from request headers."
  [request]
  (or (first-header-part request "X-Forwarded-Proto")
      (forwarded-param request #"(?i)proto=([^;,]+)")))

(defn forwarded-host
  "Reads the first forwarded host value from request headers."
  [request]
  (or (first-header-part request "X-Forwarded-Host")
      (forwarded-param request #"(?i)host=([^;,]+)")))

(defn forwarded-for
  "Reads the first forwarded client address from request headers."
  [request]
  (or (first-header-part request "X-Forwarded-For")
      (forwarded-param request #"(?i)for=([^;,]+)")))

(defn- parse-port
  "Parses a numeric port string."
  [port]
  (when (seq port)
    (let [n (js/Number port)]
      (when-not (js/isNaN n)
        n))))

(defn split-host-port
  "Splits a forwarded host value into :server-name and optional :server-port."
  [host]
  (let [host (str/trim (str host))]
    (cond
      (str/blank? host)
      {}

      (str/starts-with? host "[")
      (let [[_ address port] (re-find #"^\[([^\]]+)\](?::(\d+))?$" host)]
        (cond-> {:server-name address}
          (parse-port port)
          (assoc :server-port (parse-port port))))

      :else
      (let [[name port] (str/split host #":" 2)]
        (cond-> {:server-name name}
          (parse-port port)
          (assoc :server-port (parse-port port)))))))

(defn real-ip
  "Returns the best client IP candidate from configured forwarding headers."
  ([request]
   (real-ip request {}))
  ([request options]
   (let [headers (or (:real-ip-headers options) default-real-ip-headers)]
     (or (some #(first-header-part request %) headers)
         (:remote-addr request)))))

(defn proxy-headers-request
  "Associates forwarded scheme, host, port, and real IP fields onto the request map."
  ([request]
   (proxy-headers-request request {}))
  ([request options]
   (let [proto (forwarded-proto request)
         host (forwarded-host request)
         ip (real-ip request options)]
     (cond-> request
       proto
       (assoc :scheme (keyword (str/lower-case proto)))

       host
       (merge (split-host-port host))

       ip
       (assoc :real-ip ip
              :remote-addr ip)))))

(defn real-ip-request
  "Associates only the derived real client IP without changing scheme or host."
  ([request]
   (real-ip-request request {}))
  ([request options]
   (let [ip (real-ip request options)]
     (cond-> request
       ip
       (assoc :real-ip ip
              :remote-addr ip)))))

(defn wrap-forwarded-headers
  "Wraps a handler so forwarding headers update request connection fields."
  ([handler]
   (wrap-forwarded-headers handler {}))
  ([handler options]
   (common/wrap-request handler #(proxy-headers-request % options))))

(defn wrap-real-ip
  "Wraps a handler so real-IP headers update :real-ip and :remote-addr."
  ([handler]
   (wrap-real-ip handler {}))
  ([handler options]
   (common/wrap-request handler #(real-ip-request % options))))
