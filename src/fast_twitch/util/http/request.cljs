(ns fast-twitch.util.http.request
  (:require [clojure.string :as str]
            [fast-twitch.util.http.headers :as headers]
            [fast-twitch.util.http.body :as body])
  (:refer-global :only [Request URL Object Number Error]))

(defn- query-string
  "Extracts the query string without the leading question mark."
  [url]
  (let [search (aget url "search")] (when (pos? (count search)) (subs search 1))))

(defn- url-scheme
  "Returns the URL protocol as a lowercase keyword."
  [url]
  (let [protocol (aget url "protocol")]
    (keyword (subs protocol 0 (dec (count protocol))))))

(defn- url-port
  "Returns the explicit or default port for a URL and scheme."
  [url scheme]
  (Number (or (not-empty (aget url "port"))
              (case scheme
                :https "443"
                "80"))))

(defn- entries-map
  "Converts entry pairs into a keyword-keyed map."
  [entries]
  (into {}
        (map (fn [entry] [(keyword (aget entry 0)) (aget entry 1)]))
        entries))

(defn- headers-map
  "Converts a Fetch Headers instance into a keyword-keyed map."
  [headers]
  (entries-map (.entries headers)))

(defn- path-params
  "Extracts pathname group matches from a URLPattern execution result."
  [params]
  (when-let [groups (some-> params
                            (aget "pathname")
                            (aget "groups"))]
    (entries-map (Object.entries groups))))

(defn- required
  "Reads a required option or throws an explanatory error."
  [options k]
  (or (when (contains? options k) (req! options k))
      (throw (Error. (str "ft request option required: " k)))))

(defn request->map
  "Builds the request map consumed by application handlers."
  ([request]
   (request->map request {:protocol "HTTP/1.1" :remote-addr "unknown"}))
  ([request options]
   (request->map request nil options))
  ([request params options]
   (let [url (URL. (aget request "url"))
         scheme (or (:scheme options) (url-scheme url))
         query-string (query-string url)
         path-params (path-params params)
         body (aget request "body")]
     (cond-> {:headers (headers-map (aget request "headers"))
              :fast-twitch.routing/request request
              :protocol (required options :protocol)
              :remote-addr (required options :remote-addr)
              :request-method (keyword (.toLowerCase (aget request "method")))
              :scheme scheme
              :server-name (or (:server-name options) (aget url "hostname"))
              :server-port (or (:server-port options) (url-port url scheme))
              :uri (aget url "pathname")}
       query-string (assoc :query-string query-string)
       path-params (assoc :path-params path-params)
       body (assoc :body body)))))

(defn request-url
  "Builds a URL from Ring components or an explicit outbound URL."
  ([m]
   (request-url m :outbound-description))
  ([m representation]
   (or (when (= representation :outbound-description) (:url m))
       (str (name (req! m :scheme))
            "://"
            (:server-name m)
            (when (:server-port m) (str ":" (:server-port m)))
            (:uri m)
            (when-let [q (:query-string m)] (str "?" q))))))

(defn map->request
  "Converts an outbound description or Ring map using one native constructor.
  :outbound-description accepts :url and preserves an unchanged native origin.
  :ring-map reads Ring URL components, ignores origin, and retains its header/body
  coercion contract. Constructor options apply only to outbound descriptions."
  ([m]
   (map->request m {}))
  ([m options]
   (map->request m options :outbound-description))
  ([m options representation]
   (let [ring-map? (case representation
                     :ring-map true
                     :outbound-description false
                     (throw (ex-info "Unknown request representation"
                                     {:code :fast-twitch.contract/invalid-option
                                      :path [:representation]})))
         origin (when-not ring-map? (:fast-twitch.routing/request m))
         baseline (when origin (request->map origin))
         url (request-url m representation)
         method (str/upper-case (name (req! m :request-method)))
         relevant [:headers :request-method :scheme :server-name :server-port :uri
                   :query-string :body]]
     (when origin
       (body/available! (when (identical? (:body m) (.-body origin)) origin) (:body m)))
     (if (and origin
              (empty? options)
              (not (:url m))
              (= (select-keys m relevant) (select-keys baseline relevant)))
       origin
       (let [init #js {:method method
                       :headers (if ring-map?
                                  (clj->js (headers/ring-entries (:headers m)))
                                  (headers/native (if baseline
                                                    (headers/edit-deltas (:headers
                                                                           baseline)
                                                                         (:headers m))
                                                    (:headers m))))}
             inherited ["signal" "credentials" "cache" "mode" "redirect" "referrer"
                        "referrerPolicy" "integrity" "keepalive"]]
         (when origin (doseq [k inherited] (aset init k (aget origin k))))
         (when-not ring-map? (doseq [[k v] options] (aset init (name k) v)))
         (when (if ring-map? (boolean (:body m)) (some? (:body m)))
           (aset init "body" (if ring-map? (clj->js (:body m)) (:body m)))
           (aset init "duplex" "half"))
         (Request. url init))))))
