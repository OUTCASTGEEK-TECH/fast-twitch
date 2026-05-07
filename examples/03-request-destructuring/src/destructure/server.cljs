(ns destructure.server
  [:require-macros [fast-twitch.macros :refer [env-var]]]
  [:require
   [cljs.pprint :as pprint]
   [fast-twitch.middlewares.keyword-params :as keyword-params]
   [fast-twitch.middlewares.nested-params :as nested-params]
   [fast-twitch.middlewares.params :as params]
   [fast-twitch.routing :as routing :refer [start-server! stop-server!]]
   [integrant.core :as ig]
   [replicant.string :as html]
   [destructure.views :as views]]
  [:refer-global :only [Number Response console globalThis]])

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(defn pretty [value]
  (with-out-str
    (pprint/pprint value)))

(defn text-response [status value]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (pretty value)})

(defn html-response [page]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/render page)})

(defn log-and-respond [label value]
  (console/log (str "\n" label "\n" (pretty value)))
  (text-response 200 value))

(defn hello-handler
  "Destructure common request-map keys, then destructure those smaller maps."
  [{:keys [request-method uri path-params query-params headers]}]
  (let [{:keys [name]} path-params
        {:keys [loud topic]} query-params
        learner-header (:x-learner headers)
        greeting (cond-> (str "Hello, " name)
                   (= "true" loud) (.toUpperCase))]
    (log-and-respond
     "GET /hello/:name destructured data"
     {:greeting greeting
      :topic topic
      :learner-header learner-header
      :request-method request-method
      :uri uri
      :path-params path-params
      :query-params query-params})))

(defn form-handler
  "URL-encoded form bodies become :form-params and are also merged into :params."
  [{:keys [request-method form-params params]}]
  (let [{:keys [color level]} form-params]
    (log-and-respond
     "POST /form destructured data"
     {:request-method request-method
      :favorite-colors color
      :level level
      :form-params form-params
      :all-params params})))

(defn profile-handler
  "Names like user[name] become nested maps after nested-params middleware runs."
  [{:keys [params form-params]}]
  (let [{:keys [user]} params
        {:keys [name role language]} user]
    (log-and-respond
     "POST /profile destructured data"
     {:name name
      :role role
      :language language
      :nested-user user
      :raw-form-params form-params})))

(defn body-text-handler
  "Raw bodies are streams. This async handler reads the stream into text."
  [{:keys [headers body]} respond raise]
  (if body
    (-> (Response. body)
        (.text)
        (.then (fn [text]
                 (respond
                  (log-and-respond
                   "POST /body-text destructured data"
                   {:content-type (:content-type headers)
                    :character-count (count text)
                    :body text}))))
        (.catch raise))
    (respond
     (text-response
      400
      {:error "No request body was sent."}))))

(defn default-handler [{:keys [uri]}]
  (text-response
   404
   {:error "No matching route"
    :uri uri}))

(defmethod ig/init-key ::settings [_ options]
  options)

(defmethod ig/init-key ::pages [_ {:keys [settings]}]
  {:home (fn [_request]
           (html-response (views/demo-page settings :all)))
   :form (fn [_request]
           (html-response (views/demo-page settings :form)))
   :profile (fn [_request]
              (html-response (views/demo-page settings :profile)))
   :body-text (fn [_request]
                (html-response (views/demo-page settings :body-text)))})

(defmethod ig/init-key ::routes [_ {:keys [pages]}]
  [{:pattern "/" :method :get :handler (:home pages)}
   {:pattern "/hello/:name" :method :get :handler hello-handler}
   {:pattern "/form" :method :get :handler (:form pages)}
   {:pattern "/form" :method :post :handler form-handler}
   {:pattern "/profile" :method :get :handler (:profile pages)}
   {:pattern "/profile" :method :post :handler profile-handler}
   {:pattern "/body-text" :method :get :handler (:body-text pages)}
   {:pattern "/body-text" :method :post :async-handler body-text-handler}])

(defmethod ig/init-key ::app [_ {:keys [routes]}]
  (-> (routing/routes routes default-handler)
      (keyword-params/wrap-keyword-params)
      (nested-params/wrap-nested-params)
      (params/wrap-params)))

(def config
  {::settings {:title "Request destructuring"
               :bulma-css "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"}
   ::pages {:settings (ig/ref ::settings)}
   ::routes {:pages (ig/ref ::pages)}
   ::app {:routes (ig/ref ::routes)}})

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
                 (str "Request destructuring server listening at http://"
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))}))

(set! *main-cli-fn* run)

(defn ^:export fetch [request]
  (handler request))
