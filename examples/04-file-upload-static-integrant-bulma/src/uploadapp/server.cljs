(ns uploadapp.server
  [:require-macros [fast-twitch.macros :refer [env-var]]]
  [:require
   [fast-twitch.middlewares.file :as file]
   [fast-twitch.middlewares.keyword-params :as keyword-params]
   [fast-twitch.middlewares.multipart-params :as multipart-params]
   [fast-twitch.middlewares.nested-params :as nested-params]
   [fast-twitch.routing :as routing :refer [start-server! stop-server!]]
   [integrant.core :as ig]
   [replicant.string :as html]
   [uploadapp.views :as views]]
  [:refer-global :only [Number console globalThis]])

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(defn html-response
  ([page]
   (html-response 200 page))
  ([status page]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (html/render page)}))

(defn missing-file-summary []
  {:filename "No file selected"
   :content-type "n/a"
   :size 0
   :note "n/a"})

(defn summarize-upload [params]
  (let [upload (:upload params)
        file (:file upload)]
    (if file
      {:filename (or (:filename file) "unnamed")
       :content-type (or (:content-type file) "not provided")
       :size (or (:size file) 0)
       :note (or (:note upload) "")}
      (missing-file-summary))))

(defn not-found-page [settings uri]
  (html-response
   404
   (views/not-found-page settings uri)))

(defmethod ig/init-key ::settings [_ options]
  options)

(defmethod ig/init-key ::pages [_ {:keys [settings]}]
  {:home (fn [_request]
           (html-response (views/home-page settings)))
   :upload (fn [{:keys [params]}]
             (let [summary (summarize-upload params)]
               (console/log (str "Upload summary: " (pr-str summary)))
               (html-response (views/uploaded-page settings summary))))
   :not-found (fn [{:keys [uri]}]
                (not-found-page settings uri))})

(defmethod ig/init-key ::routes [_ {:keys [pages]}]
  [{:pattern "/" :method :get :handler (:home pages)}
   {:pattern "/upload" :method :get :handler (:home pages)}
   {:pattern "/upload" :method :post :handler (:upload pages)}])

(defmethod ig/init-key ::app [_ {:keys [routes pages static-root]}]
  (-> (routing/routes routes (:not-found pages))
      (keyword-params/wrap-keyword-params)
      (nested-params/wrap-nested-params)
      (multipart-params/wrap-multipart-params)
      (file/wrap-file static-root {:url-root "static"})))

(def config
  {::settings {:title "File Upload and Static Files"
               :bulma-css "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"}
   ::pages {:settings (ig/ref ::settings)}
   ::routes {:pages (ig/ref ::pages)}
   ::app {:routes (ig/ref ::routes)
          :pages (ig/ref ::pages)
          :static-root "static"}})

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
                 (str "Upload/static server listening at http://"
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))}))

(set! *main-cli-fn* run)

(defn ^:export fetch [request]
  (handler request))
