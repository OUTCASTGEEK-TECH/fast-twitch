(ns fast-twitch.routing
  "Routing and handler adaptation helpers for translating between Fetch APIs and request maps."
  [:require-macros [fast-twitch.macros :refer [serve shutdown]]]
  [:require
   [cljs.proxy :refer [builder]]
   [fast-twitch.macros]]
  [:refer-global :only [AbortController Error Headers Number Object Promise
                        Request Response URL URLPattern WeakMap console
                        globalThis]])

(def proxy (builder))

(defn response
  "Builds a 200 response map with the supplied body."
  [body]
  {:status 200
   :headers {}
   :body body})

(defn status
  "Creates a bare response for a status code or updates an existing response map."
  ([status]
   {:status status
    :headers {}
    :body nil})
  ([response status]
   (assoc response :status status)))

(defn header
  "Associates a header value on a response map."
  [response name value]
  (assoc-in response [:headers name] (str value)))

(defn not-found
  "Builds a 404 response map with the supplied body."
  [body]
  {:status 404
   :headers {}
   :body body})

(defn response?
  "Returns true when a value matches the expected response map shape."
  [response]
  (and (map? response)
       (integer? (:status response))
       (map? (:headers response))))

(defn url-pattern
  "Builds a URLPattern that matches the given pathname."
  [pathname]
  (URLPattern. (clj->js {:pathname pathname})))

(defn- query-string
  "Extracts the query string without the leading question mark."
  [url]
  (let [search (aget url "search")]
    (when (pos? (count search))
      (subs search 1))))

(defn- url-scheme
  "Returns the URL protocol as a lowercase keyword."
  [url]
  (let [protocol (aget url "protocol")]
    (keyword (subs protocol 0 (dec (count protocol))))))

(defn- url-port
  "Returns the explicit or default port for a URL and scheme."
  [url scheme]
  (Number
   (or (not-empty (aget url "port"))
       (case scheme
         :https "443"
         "80"))))

(defn- entries-map
  "Converts entry pairs into a keyword-keyed map."
  [entries]
  (into {}
        (map (fn [entry]
               [(keyword (aget entry 0)) (aget entry 1)]))
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
  (or (get options k)
      (throw (Error. (str "ft request option required: " k)))))

(defn build-request-map
  "Builds the request map consumed by application handlers."
  ([request options]
   (build-request-map request nil options))
  ([request params options]
   (let [url (URL. (aget request "url"))
         scheme (or (:scheme options) (url-scheme url))
         query-string (query-string url)
         path-params (path-params params)
         body (aget request "body")]
     (cond-> {:headers (headers-map (aget request "headers"))
              ::request request
              :protocol (required options :protocol)
              :remote-addr (required options :remote-addr)
              :request-method (keyword (.toLowerCase (aget request "method")))
              :scheme scheme
              :server-name (or (:server-name options)
                               (aget url "hostname"))
              :server-port (or (:server-port options)
                               (url-port url scheme))
              :uri (aget url "pathname")}
       query-string
       (assoc :query-string query-string)

       path-params
       (assoc :path-params path-params)

       body
       (assoc :body body)))))

(defn- header-entries
  "Expands response headers into entries, preserving multi-value headers."
  [headers]
  (mapcat (fn [[k v]]
            (if (vector? v)
              (map #(vector k %) v)
              [[k v]]))
          headers))

(defn- response-body
  "Normalizes sequential response bodies into a single string."
  [body]
  (if (sequential? body)
    (apply str body)
    body))

(defn- update-response
  "Converts a response map into a Fetch Response instance."
  [response]
  (Response.
   (response-body (:body response))
   (proxy {:status (:status response)
           :headers (header-entries (:headers response))})))

(defn- rejected-promise
  "Creates a promise already rejected with the supplied error."
  [error]
  (Promise. (fn [_ raise] (raise error))))

(defn- handler-promise
  "Calls a handler and captures thrown errors as rejected promises."
  [handler request]
  (try
    (Promise.resolve (handler request))
    (catch :default error
      (rejected-promise error))))

(defn ft-handler
  "Wraps an application handler as a Fetch-compatible function."
  [handler options]
  (if (:async? options)
    (let [handle (fn [request]
                   (Promise.
                    (fn [respond raise]
                      (handler (build-request-map request options)
                               #(respond (update-response %))
                               raise))))]
      (fn
        ([request]
         (handle request))
        ([request _info]
         (handle request))))
    (let [handle (fn [request]
                   (-> (handler-promise handler
                                        (build-request-map request options))
                       (.then update-response)))]
      (fn
        ([request]
         (handle request))
        ([request _info]
         (handle request))))))

(defn- route-method
  "Normalizes a method value into its uppercase string form."
  [method]
  (cond
    (keyword? method) (.toUpperCase (name method))
    (string? method) (.toUpperCase method)
    :else method))

(defn- route-entry
  "Normalizes one route definition into the internal route entry shape."
  [{:keys [pattern method handler async-handler]}]
  {:pattern (if (string? pattern)
              (url-pattern pattern)
              pattern)
   :method method
   :handler handler
   :async-handler async-handler})

(defn- method-matches?
  "Returns true when a route method matches the incoming request method."
  [method request-method]
  (let [request-method (route-method request-method)]
    (cond
      (nil? method)
      true

      (sequential? method)
      (some #(= (route-method %) request-method) method)

      :else
      (= (route-method method) request-method))))

(defn- request-url
  "Builds a URL string for route matching from a request map."
  [request]
  (or (some-> (::request request) (aget "url"))
      (str (name (:scheme request))
           "://"
           (:server-name request)
           (when-let [port (:server-port request)]
             (str ":" port))
           (:uri request)
           (when-let [query-string (:query-string request)]
             (str "?" query-string)))))

(defn- route-match
  "Returns route data with extracted path params when a route matches."
  [request route]
  (when (method-matches? (:method route) (:request-method request))
    (when-let [params (.exec (:pattern route) (request-url request))]
      (assoc route :path-params (path-params params)))))

(defn- route-request
  "Associates matched path params onto the request map."
  [request route]
  (cond-> request
    (:path-params route)
    (assoc :path-params (:path-params route))))

(defn- route-response
  "Invokes the matching route handler in sync or async form."
  [route request]
  (let [request (route-request request route)]
    (if-let [async-handler (:async-handler route)]
      (Promise.
       (fn [respond raise]
         (async-handler request respond raise)))
      ((:handler route) request))))

(defn- respond-to
  "Delivers a response through async callbacks with promise-aware error handling."
  [response respond raise]
  (-> (Promise.resolve response)
      (.then respond)
      (.catch raise)))

(defn routes
  "Builds a dispatching handler from route definitions and a fallback handler."
  [routes default-handler]
  (let [routes (mapv route-entry routes)]
    (fn
      ([request]
       (if-let [route (some #(route-match request %) routes)]
         (route-response route request)
         (default-handler request)))
      ([request respond raise]
       (try
         (let [response (if-let [route (some #(route-match request %) routes)]
                          (route-response route request)
                          (default-handler request))]
           (respond-to response respond raise))
         (catch :default error
           (raise error)))))))

(defonce server* (atom nil))

(defn start-server!
  "Starts the runtime adapter for an application or handler."
  ([app]
   (reset! server* (serve :app app)))
  ([handler options]
   (reset! server* (serve :app (proxy (assoc options :handler handler))))))

(defn stop-server! [& {:keys [force callback]}]
  (when-let [server @server*]
    (-> (shutdown server :force force)
        (.then (fn []
                 (reset! server* nil)
                 (when callback
                   (callback)))))))
