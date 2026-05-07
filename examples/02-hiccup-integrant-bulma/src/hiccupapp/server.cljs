(ns hiccupapp.server
  [:require-macros [fast-twitch.macros :refer [env-var]]]
  [:require
   [fast-twitch.routing :as routing :refer [start-server! stop-server!]]
   [integrant.core :as ig]
   [replicant.string :as html]
   [hiccupapp.views :as views]]
  [:refer-global :only [Number console globalThis]])

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(defn html-response [page]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/render page)})

(defn not-found-page [settings uri]
  (assoc
   (html-response
    (views/not-found-page settings uri))
   :status 404))

(defmethod ig/init-key ::settings [_ options]
  options)

(defmethod ig/init-key ::pages [_ {:keys [settings]}]
  {:home (fn [_request]
           (html-response (views/home-page settings)))
   :hello (fn [{:keys [path-params]}]
            (html-response
             (views/hello-page settings (:name path-params))))
   :not-found (fn [{:keys [uri]}]
                (not-found-page settings uri))})

(defmethod ig/init-key ::routes [_ {:keys [pages]}]
  [{:pattern "/" :method :get :handler (:home pages)}
   {:pattern "/hello/:name" :method :get :handler (:hello pages)}])

(defmethod ig/init-key ::app [_ {:keys [routes pages]}]
  (routing/routes routes (:not-found pages)))

(def config
  {::settings {:title "Hiccup through Integrant"
               :bulma-css "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"}
   ::pages {:settings (ig/ref ::settings)}
   ::routes {:pages (ig/ref ::pages)}
   ::app {:routes (ig/ref ::routes)
          :pages (ig/ref ::pages)}})

(defonce system
  (ig/init config))

(def handler
  (routing/ft-handler (::app system) request-options))

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
                 (str "Hiccup Integrant server listening at http://"
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))}))

(set! *main-cli-fn* run)

(defn ^:export fetch [request]
  (handler request))
