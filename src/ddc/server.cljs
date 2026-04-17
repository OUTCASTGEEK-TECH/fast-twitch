(ns ddc.server
  [:require-macros [ddc.macros :refer [env-var serve]]]
  [:require
   [cljs.proxy :refer [builder]]
   [ddc.macros]
   ["@std/http/unstable-route" :refer [route]]]
  [:refer-global :only [Error Headers Number Object Promise Request Response URLPattern console globalThis]])

(def proxy (builder))

(defn url-pattern [pathname]
  (URLPattern. (clj->js {:pathname pathname
                         :hostname "*"
                         :baseURL "http://localhost"})))

(defn response-init [init]
  (clj->js
   (cond-> {:headers {"content-type" "text/plain"}}
     (:status init)
     (assoc :status (:status init)))))

(defn text-response
  ([body]
   (text-response body {}))
  ([body init]
   (Response.
    body
    (response-init init))))

(defn home-handler
  [_request]
  (text-response "Hello from data-driven-clarity\n"))

(defn health-handler
  [_request]
  (text-response "ok\n"))

(defn default-handler
  [_request]
  (text-response "Not found\n" {:status 404}))

(def routes
  (proxy
   [{:pattern (url-pattern "/")
     :method "GET"
     :handler home-handler}
    {:pattern (url-pattern "/health")
     :method "GET"
     :handler health-handler}]))

(def handler
  (route routes default-handler))

(def app
  (proxy
   {:handler handler
    :port (env-var "PORT")
    :hostname (or (env-var "HOSTNAME") "127.0.0.1")
    :onListen (fn [addr]
                (console/log
                 (str "Server running at "
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))
    :reusePort (= "true" (env-var "REUSE_PORT"))}))

(defn main []
  (serve :app app))

(set! *main-cli-fn* main)
