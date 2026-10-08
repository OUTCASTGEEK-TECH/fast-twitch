(ns fast-twitch.routing
  "Routing and handler adaptation helpers for translating between Fetch APIs and request maps."
  [:require-macros [fast-twitch.macros :refer [serve shutdown]]]
  [:require [cljs.core :refer [await]]
            [cljs.proxy :refer [builder]]
            [fast-twitch.macros]
            [fast-twitch.util.http.request :as http-request]
            [fast-twitch.util.http.response :as http-response]
            [fast-twitch.server.websocket :as upgrade]
            [fast-twitch.util.async.settlement :as settlement]]
  [:refer-global :only
                 [AbortController Error Headers Number Object Promise Request Response URL
                  URLPattern WeakMap console globalThis]])

(def proxy
  (builder))

(defn response
  "Builds a 200 response map with the supplied body."
  [body]
  {:status 200 :headers {} :body body})

(defn status
  "Creates a bare response for a status code or updates an existing response map."
  ([status]
   {:status status :headers {} :body nil})
  ([response status]
   (assoc response :status status)))

(defn header
  "Associates a header value on a response map."
  [response name value]
  (assoc-in response [:headers name] (str value)))

(defn not-found
  "Builds a 404 response map with the supplied body."
  [body]
  {:status 404 :headers {} :body body})

(defn response?
  "Returns true when a value matches the expected response map shape."
  [response]
  (and (map? response) (integer? (:status response)) (map? (:headers response))))

(defn url-pattern
  "Builds a URLPattern that matches the given pathname."
  [pathname]
  (URLPattern. (clj->js {:pathname pathname})))

(defn- path-params
  [params]
  (when-let [groups (some-> params
                            (aget "pathname")
                            (aget "groups"))]
    (into {}
          (map (fn [entry] [(keyword (aget entry 0)) (aget entry 1)]))
          (Object.entries groups))))

(defn build-request-map
  ([request options]
   (http-request/request->map request options))
  ([request params options]
   (http-request/request->map request params options)))

(defn ft-handler
  "Wraps an application handler as a Fetch-compatible function."
  [handler options]
  (let [finalize (fn [native value]
                   (if (upgrade/listener-response? value)
                     (upgrade/upgrade! [native value])
                     (http-response/map->response (http-response/normalize value))))
        handle (fn [native]
                 (if (:async? options)
                   (settlement/callback-promise
                     (fn [respond raise]
                       (handler (build-request-map native options) respond raise))
                     #(finalize native %))
                   ((^:async fn
                     []
                     (finalize
                       native
                       (await (handler (build-request-map native options))))))))]
    (fn
      ([request]
       (handle request))
      ([request _info]
       (handle request)))))

(defn- route-method
  "Normalizes a method value into its uppercase string form."
  [method]
  (cond (keyword? method) (.toUpperCase (name method))
        (string? method) (.toUpperCase method)
        :else method))

(defn- route-entry
  "Normalizes one route definition into the internal route entry shape."
  [{:keys [pattern method handler async-handler]}]
  {:pattern (if (string? pattern) (url-pattern pattern) pattern)
   :method method
   :handler handler
   :async-handler async-handler})

(defn- method-matches?
  "Returns true when a route method matches the incoming request method."
  [method request-method]
  (let [request-method (route-method request-method)]
    (cond (nil? method) true
          (sequential? method) (some #(= (route-method %) request-method) method)
          :else (= (route-method method) request-method))))

(defn- request-url
  "Builds a URL string for route matching from a request map."
  [request]
  (if-let [uri (:uri request)]
    (str (name (:scheme request))
         "://"
         (:server-name request)
         (when-let [port (:server-port request)] (str ":" port))
         uri
         (when-let [query-string (:query-string request)] (str "?" query-string)))
    (some-> (::request request)
            (aget "url"))))

(defn- route-match
  "Returns route data with extracted path params when a route matches."
  [request route]
  (when (method-matches? (:method route) (:request-method request))
    (when-let [params (.exec (:pattern route) (request-url request))]
      (assoc route :path-params (path-params params)))))

(defn- route-request
  "Associates matched path params onto the request map."
  [request route]
  (cond-> request (:path-params route) (assoc :path-params (:path-params route))))

(defn- route-response
  "Invokes the matching route handler in sync or async form."
  [route request]
  (let [request (route-request request route)]
    (upgrade/bind! request)
    (if-let [async-handler (:async-handler route)]
      (settlement/callback-promise (fn [respond raise]
                                     (async-handler request respond raise))
                                   identity)
      ((:handler route) request))))

(defn- respond-to
  "Delivers a response through async callbacks with promise-aware error handling."
  [response respond raise]
  ((^:async fn
    []
    (try (await (respond (await response)))
         (catch :default error (raise error))))))

(defn routes
  "Builds a dispatching handler from route definitions and a fallback handler."
  [routes default-handler]
  (let [routes (mapv route-entry routes)]
    (fn
      ([request]
       (if-let [route (some #(route-match request %) routes)]
         (route-response route request)
         (route-response {:handler default-handler} request)))
      ([request respond raise]
       (try (let [response (if-let [route (some #(route-match request %) routes)]
                             (route-response route request)
                             (route-response {:handler default-handler} request))]
              (respond-to response respond raise))
            (catch :default error (raise error)))))))

(defonce server*
  (atom nil))

(defn start-server!
  "Starts the runtime adapter for an application or handler."
  ([app]
   (reset! server* (serve :app app)))
  ([handler options]
   (reset! server* (serve :app (proxy (assoc options :handler handler))))))

(defn stop-server!
  [& {:keys [force callback]}]
  (when-let [server @server*]
    ((^:async fn
      []
      (await (shutdown server :force force))
      (reset! server* nil)
      (when callback (callback))))))
