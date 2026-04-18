(ns fast-twitch.routing
  [:require-macros [fast-twitch.macros :refer [serve]]]
  [:require
   [cljs.proxy :refer [builder]]
   [fast-twitch.macros]]
  [:refer-global :only [Error Headers Number Object Promise Request Response
                        URL URLPattern console globalThis]])

(def proxy (builder))

(defn response [body]
  {:status 200
   :headers {}
   :body body})

(defn status
  ([status]
   {:status status
    :headers {}
    :body nil})
  ([response status]
   (assoc response :status status)))

(defn header [response name value]
  (assoc-in response [:headers name] (str value)))

(defn not-found [body]
  {:status 404
   :headers {}
   :body body})

(defn response? [response]
  (and (map? response)
       (integer? (:status response))
       (map? (:headers response))))

(defn url-pattern [pathname]
  (URLPattern. (clj->js {:pathname pathname})))

(defn- query-string [url]
  (let [search (aget url "search")]
    (when (pos? (count search))
      (subs search 1))))

(defn- url-scheme [url]
  (let [protocol (aget url "protocol")]
    (keyword (subs protocol 0 (dec (count protocol))))))

(defn- url-port [url scheme]
  (Number
   (or (not-empty (aget url "port"))
       (case scheme
         :https "443"
         "80"))))

(defn- entries-map [entries]
  (into {}
        (map (fn [entry]
               [(keyword (aget entry 0)) (aget entry 1)]))
        entries))

(defn- headers-map [headers]
  (entries-map (.entries headers)))

(defn- path-params [params]
  (when-let [groups (some-> params
                            (aget "pathname")
                            (aget "groups"))]
    (entries-map (Object.entries groups))))

(defn- required [options k]
  (or (get options k)
      (throw (Error. (str "ft request option required: " k)))))

(defn build-request-map
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

(defn- header-entries [headers]
  (mapcat (fn [[k v]]
            (if (vector? v)
              (map #(vector k %) v)
              [[k v]]))
          headers))

(defn- response-body [body]
  (if (sequential? body)
    (apply str body)
    body))

(defn- update-response [response]
  (Response.
   (response-body (:body response))
   (proxy {:status (:status response)
           :headers (header-entries (:headers response))})))

(defn- rejected-promise [error]
  (Promise. (fn [_ raise] (raise error))))

(defn- handler-promise [handler request]
  (try
    (Promise.resolve (handler request))
    (catch :default error
      (rejected-promise error))))

(defn ft-handler [handler options]
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

(defn- route-method [method]
  (cond
    (keyword? method) (.toUpperCase (name method))
    (string? method) (.toUpperCase method)
    :else method))

(defn- route-entry [{:keys [pattern method handler async-handler]}]
  {:pattern (if (string? pattern)
              (url-pattern pattern)
              pattern)
   :method method
   :handler handler
   :async-handler async-handler})

(defn- method-matches? [method request-method]
  (let [request-method (route-method request-method)]
    (cond
      (nil? method)
      true

      (sequential? method)
      (some #(= (route-method %) request-method) method)

      :else
      (= (route-method method) request-method))))

(defn- request-url [request]
  (or (some-> (::request request) (aget "url"))
      (str (name (:scheme request))
           "://"
           (:server-name request)
           (when-let [port (:server-port request)]
             (str ":" port))
           (:uri request)
           (when-let [query-string (:query-string request)]
             (str "?" query-string)))))

(defn- route-match [request route]
  (when (method-matches? (:method route) (:request-method request))
    (when-let [params (.exec (:pattern route) (request-url request))]
      (assoc route :path-params (path-params params)))))

(defn- route-request [request route]
  (cond-> request
    (:path-params route)
    (assoc :path-params (:path-params route))))

(defn- route-response [route request]
  (let [request (route-request request route)]
    (if-let [async-handler (:async-handler route)]
      (Promise.
       (fn [respond raise]
         (async-handler request respond raise)))
      ((:handler route) request))))

(defn- respond-to [response respond raise]
  (-> (Promise.resolve response)
      (.then respond)
      (.catch raise)))

(defn routes [routes default-handler]
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

(defn run-adapter
  ([app]
   (serve :app app))
  ([handler options]
   (serve :app (proxy (assoc options :handler handler)))))
