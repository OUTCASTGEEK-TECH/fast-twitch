(ns ddc.server
  [:require-macros [ddc.macros :refer [env-var serve]]]
  [:require
   [cljs.proxy :refer [builder]]
   [clojure.string :as str]
   [ddc.macros]
   [ddc.routing :as routing]
   [replicant.string :as html]]
  [:refer-global :only [Error Headers Number Object Promise Request Response
                        URLPattern console globalThis]])

(def proxy (builder))

(defonce todos
  (atom {1 {:id 1 :title "Wire Ring request maps" :done? true}
         2 {:id 2 :title "Render todos with Replicant" :done? false}}))

(defonce next-todo-id (atom 2))

(defonce sessions (atom {}))

(def session-cookie-name "ddc_session")

(defn todo-list []
  (sort-by :id (vals @todos)))

(defn html-response
  ([body]
   (html-response body 200))
  ([body status]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (str "<!doctype html>" (html/render body))}))

(defn redirect [location]
  {:status 303
   :headers {"Location" location}
   :body ""})

(defn redirect-with-headers [location headers]
  (assoc (redirect location) :headers (merge {"Location" location} headers)))

(defn parse-cookie [cookie]
  (into {}
        (keep (fn [part]
                (let [[k v] (str/split (str/trim part) #"=" 2)]
                  (when (seq k)
                    [k (or v "")]))))
        (str/split (or cookie "") #";")))

(defn request-cookies [request]
  (parse-cookie (get-in request [:headers :cookie])))

(defn current-user [request]
  (some->> (get (request-cookies request) session-cookie-name)
           (get @sessions)))

(defn authenticated [request handler]
  (if-let [user (current-user request)]
    (handler (assoc request :user user))
    (redirect "/login")))

(defn authenticated-async [request respond handler]
  (if-let [user (current-user request)]
    (handler (assoc request :user user))
    (respond (redirect "/login"))))

(defn session-cookie [token]
  (str session-cookie-name "=" token
       "; Path=/; HttpOnly; SameSite=Lax"))

(defn expired-session-cookie []
  (str session-cookie-name "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"))

(defn valid-credentials? [{:keys [username password]}]
  (and (= username (or (env-var "TODO_USER") "admin"))
       (= password (or (env-var "TODO_PASSWORD") "password"))))

(defn layout [{:keys [title user]} & body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title title]
    [:link {:rel "stylesheet"
            :href "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"}]
    [:style
     ".is-done{text-decoration:line-through}
      .todo-row{align-items:center}
      .todo-title{overflow-wrap:anywhere}"]]
   [:body
    [:section.section
     [:div.container.is-max-desktop
      [:header.level.mb-6
       [:div.level-left
        [:div
         [:p.heading "Data-driven clarity"]
         [:h1.title.is-2 title]]]
       (when user
         [:div.level-right
          [:form {:method "post" :action "/logout"}
           [:button.button.is-light {:type "submit"} "Sign out"]]])]
      body]]]])

(defn login-page [{:keys [error]}]
  (layout {:title "Sign in"}
          (when error
            [:div.notification.is-danger.is-light error])
          [:div.columns.is-centered
           [:div.column.is-half-desktop.is-two-thirds-tablet
            [:form.box {:method "post" :action "/login"}
             [:div.field
              [:label.label {:for "username"} "User"]
              [:div.control
               [:input.input {:id "username"
                              :type "text"
                              :name "username"
                              :autocomplete "username"
                              :autofocus true}]]]
             [:div.field
              [:label.label {:for "password"} "Password"]
              [:div.control
               [:input.input {:id "password"
                              :type "password"
                              :name "password"
                              :autocomplete "current-password"}]]]
             [:div.field
              [:div.control
               [:button.button.is-primary.is-fullwidth {:type "submit"}
                "Sign in"]]]]]]))

(defn todo-item [{:keys [id title done?]}]
  [:div.box
   [:div.columns.is-mobile.todo-row
    [:div.column.is-narrow
     [:form {:method "post" :action (str "/todos/" id "/toggle")}
      [:button.button.is-small
       {:class (if done? "is-warning is-light" "is-success is-light")
        :type "submit"}
       (if done? "Undo" "Done")]]]
    [:div.column
     [:p.todo-title
      {:class (when done? "has-text-grey is-done")}
      title]]
    [:div.column.is-narrow
     [:form {:method "post" :action (str "/todos/" id "/delete")}
      [:button.button.is-small.is-danger.is-light {:type "submit"}
       "Delete"]]]]])

(defn todos-page [request]
  (let [user (:user request)
        todos (todo-list)]
    (layout {:title "Todos" :user user}
            [:div.notification.is-info.is-light
             "Signed in as " [:strong user]]
            [:form.box.mb-5 {:method "post" :action "/todos"}
             [:label.label {:for "title"} "Add a todo"]
             [:div.field.has-addons
              [:div.control.is-expanded
               [:input.input {:id "title"
                              :type "text"
                              :name "title"
                              :placeholder "New todo"
                              :required true
                              :autofocus true}]]
              [:div.control
               [:button.button.is-primary {:type "submit"} "Add"]]]]
            (if (seq todos)
              [:div (map todo-item todos)]
              [:div.notification.is-light "No todos yet."]))))

(defn root-handler [request]
  (if (current-user request)
    (redirect "/todos")
    (redirect "/login")))

(defn login-form-handler [_request]
  (html-response (login-page {})))

(defn login-handler [request respond raise]
  (-> (routing/form-params request)
      (.then
       (fn [params]
         (if (valid-credentials? params)
           (let [token (str (random-uuid))]
             (swap! sessions assoc token (:username params))
             (respond
              (redirect-with-headers
               "/todos"
               {"Set-Cookie" (session-cookie token)})))
           (respond
            (html-response
             (login-page {:error "Invalid username or password"})
             401)))))
      (.catch raise)))

(defn logout-handler [request]
  (when-let [token (get (request-cookies request) session-cookie-name)]
    (swap! sessions dissoc token))
  (redirect-with-headers
   "/login"
   {"Set-Cookie" (expired-session-cookie)}))

(defn todos-handler [request]
  (authenticated request #(html-response (todos-page %))))

(defn add-todo-handler [request respond raise]
  (authenticated-async
   request
   respond
   (fn [_request]
     (-> (routing/form-params request)
         (.then
          (fn [{:keys [title]}]
            (when (seq (str/trim (or title "")))
              (let [id (swap! next-todo-id inc)]
                (swap! todos assoc id {:id id
                                       :title (str/trim title)
                                       :done? false})))
            (respond (redirect "/todos"))))
         (.catch raise)))))

(defn todo-id [request]
  (Number (get-in request [:path-params :id])))

(defn toggle-todo-handler [request]
  (authenticated
   request
   (fn [request]
     (let [id (todo-id request)]
       (swap! todos
              (fn [todos]
                (if (contains? todos id)
                  (update-in todos [id :done?] not)
                  todos))))
     (redirect "/todos"))))

(defn delete-todo-handler [request]
  (authenticated
   request
   (fn [request]
     (swap! todos dissoc (todo-id request))
     (redirect "/todos"))))

(defn default-handler [_request]
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not found\n"})

(def routes
  [{:pattern "/" :method :get :handler root-handler}
   {:pattern "/login" :method :get :handler login-form-handler}
   {:pattern "/login" :method :post :async-handler login-handler}
   {:pattern "/logout" :method :post :handler logout-handler}
   {:pattern "/todos" :method :get :handler todos-handler}
   {:pattern "/todos" :method :post :async-handler add-todo-handler}
   {:pattern "/todos/:id/toggle" :method :post :handler toggle-todo-handler}
   {:pattern "/todos/:id/delete" :method :post :handler delete-todo-handler}])

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(def handler
  (routing/ring-routes routes default-handler request-options))

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

(defn ^:export fetch [request]
  (handler request))
