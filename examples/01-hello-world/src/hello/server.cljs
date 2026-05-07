(ns hello.server
  [:require-macros [fast-twitch.macros :refer [env-var]]]
  [:require
   [cljs.pprint :as pprint]
   [fast-twitch.routing :as routing :refer [start-server! stop-server!]]]
  [:refer-global :only [Number console globalThis]])

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(defn greet-one [name]
  (str name " is the one"))

(comment
  (greet-one "Lb")
  ;;
  )

(defn pretty
  "Turn a ClojureScript value into a nicely spaced string."
  [value]
  (with-out-str
    (pprint/pprint value)))

(defn request-for-humans
  "The real request map includes the original Fetch Request under a namespaced
  key. That object is useful to advanced users, but noisy in hello world output."
  [request]
  (dissoc request ::routing/request))

(defn hello-handler
  "A Ring-style handler: request map in, response map out."
  [request]
  (let [request-map (request-for-humans request)
        body (str "Hello from fast-twitch.\n\n"
                  "The handler received this request map:\n\n"
                  (pretty request-map))]
    (console/log "Request map received by hello-handler:")
    (console/log (pretty request-map))
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body body}))

(def handler
  (routing/ft-handler hello-handler request-options))

(defn shutdown []
  (stop-server! :force true
                :callback (fn [] (console.log "Server stopped"))))

(defn run []
  (start-server!
   handler
   {:port (Number (or (env-var "PORT") 6464))
    :hostname (or (env-var "HOSTNAME") "127.0.0.1")
    :onListen (fn [addr]
                (console/log
                 (str "Hello world server listening at http://"
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))}))

(set! *main-cli-fn* run)

(defn ^:export fetch [request]
  (handler request))
