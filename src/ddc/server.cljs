(ns ddc.server
  [:require-macros [ddc.macros :refer [env-var]]]
  [:require
   [cljs.proxy :refer [builder]]
   [clojure.string :as str]
   [ddc.macros]
   [ddc.middlewares.defaults :as defaults]
   [ddc.routing :as routing]
   [ddc.util.anti-forgery :refer [anti-forgery-field]]
   [replicant.string :as html]]
  [:refer-global :only [Error Headers Number Object Promise Request Response
                        URLPattern console globalThis]])

(def proxy (builder))

(defonce todos
  (atom {1 {:id 1 :title "Wire Ring request maps" :done? true}
         2 {:id 2 :title "Render todos with Replicant" :done? false}}))

(defonce next-todo-id (atom 2))

(def session-cookie-name "ddc_session")

(def health-etag "W/\"ddc-health-v1\"")

(def health-last-modified "Fri, 17 Apr 2026 00:00:00 GMT")

(defn todo-list []
  (sort-by :id (vals @todos)))

(defn cookie-value [request k]
  (get-in request [:cookies k :value]))

(defn user-theme [request]
  (or (cookie-value request :todo-theme) "default"))

(defn user-density [request]
  (or (cookie-value request :todo-density) "comfortable"))

(defn todo-filters [request]
  (or (get-in request [:params :filters])
      (get-in request [:session :last-filters])
      {}))

(defn todo-tags [request]
  (let [tags (or (:tags (:params request))
                 (:tags (:query-params request)))]
    (cond
      (nil? tags) []
      (sequential? tags) (vec (flatten tags))
      :else [tags])))

(defn flash-class [type]
  (case type
    :success "is-success"
    :warning "is-warning"
    :danger "is-danger"
    :info "is-info"
    "is-info"))

(defn flash-view [flash]
  (when flash
    [:div.notification.is-light
     {:class (flash-class (:type flash))}
     (:text flash)]))

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

(defn current-user [request]
  (get-in request [:session :user]))

(defn authenticated [request handler]
  (if-let [user (current-user request)]
    (handler (assoc request :user user))
    (redirect "/login")))

(defn valid-credentials? [{:keys [username password]}]
  (and (= username (or (env-var "TODO_USER") "admin"))
       (= password (or (env-var "TODO_PASSWORD") "password"))))

(defn layout [{:keys [title user flash theme density]} & body]
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
      .todo-title{overflow-wrap:anywhere}
      .request-snapshot pre{white-space:pre-wrap;word-break:break-word}
      .density-compact .box{padding:.75rem}
      .theme-dark{background:#111827;color:#f9fafb}
      .theme-dark .box,.theme-dark .notification{background:#1f2937;color:#f9fafb}
      .theme-dark .label,.theme-dark .title,.theme-dark .heading{color:#f9fafb}"]]
   [:body
    {:class [(str "theme-" theme)
             (str "density-" density)]}
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
            (anti-forgery-field)
            [:button.button.is-light {:type "submit"} "Sign out"]]])]
      (flash-view flash)
      body]]]])

(defn login-page [request {:keys [error]}]
  (layout {:title "Sign in"
           :flash (:flash request)
           :theme (user-theme request)
           :density (user-density request)}
          (when error
            [:div.notification.is-danger.is-light error])
          [:div.columns.is-centered
           [:div.column.is-half-desktop.is-two-thirds-tablet
            [:form.box {:method "post" :action "/login"}
             (anti-forgery-field)
             [:div.field
              [:label.label {:for "username"} "User"]
              [:div.control
               [:input.input {:id "username"
                              :type "text"
                              :name "credentials[username]"
                              :autocomplete "username"
                              :autofocus true}]]]
             [:div.field
              [:label.label {:for "password"} "Password"]
              [:div.control
               [:input.input {:id "password"
                              :type "password"
                              :name "credentials[password]"
                              :autocomplete "current-password"}]]]
             [:div.field
              [:div.control
               [:button.button.is-primary.is-fullwidth {:type "submit"}
                "Sign in"]]]]]]))

(defn todo-matches? [{:keys [status q]} todo]
  (let [title (str/lower-case (:title todo))
        q (str/lower-case (or q ""))]
    (and
     (case status
       "open" (not (:done? todo))
       "done" (:done? todo)
       true)
     (or (str/blank? q)
         (str/includes? title q)))))

(defn filtered-todos [request]
  (let [filters (todo-filters request)]
    (filter #(todo-matches? filters %) (todo-list))))

(defn todo-item [{:keys [id title done?]}]
  [:div.box
   [:div.columns.is-mobile.todo-row
    [:div.column.is-narrow
     [:form {:method "post" :action (str "/todos/" id "/toggle")}
      (anti-forgery-field)
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
      (anti-forgery-field)
      [:button.button.is-small.is-danger.is-light {:type "submit"}
       "Delete"]]]]])

(defn filter-form [request]
  (let [{:keys [status q]} (todo-filters request)
        tags (set (todo-tags request))]
    [:form.box.mb-5 {:method "get" :action "/todos"}
     [:div.columns
      [:div.column
       [:label.label {:for "filter-q"} "Search"]
       [:input.input {:id "filter-q"
                      :type "search"
                      :name "filters[q]"
                      :value q
                      :placeholder "Title contains"}]]
      [:div.column.is-one-quarter
       [:label.label {:for "filter-status"} "Status"]
       [:div.select.is-fullwidth
        [:select {:id "filter-status" :name "filters[status]"}
         [:option {:value "" :selected (or (nil? status) (= "" status))} "All"]
         [:option {:value "open" :selected (= "open" status)} "Open"]
         [:option {:value "done" :selected (= "done" status)} "Done"]]]]]
     [:div.field.is-grouped.is-grouped-multiline
      [:label.checkbox.mr-4
       [:input {:type "checkbox"
                :name "tags[]"
                :value "cljs"
                :checked (contains? tags "cljs")}]
       " CLJS"]
      [:label.checkbox.mr-4
       [:input {:type "checkbox"
                :name "tags[]"
                :value "ring"
                :checked (contains? tags "ring")}]
       " Ring"]
      [:label.checkbox
       [:input {:type "checkbox"
                :name "tags[]"
                :value "std"
                :checked (contains? tags "std")}]
       " @std"]]
     [:div.field.is-grouped
      [:div.control
       [:button.button.is-link {:type "submit"} "Apply"]]
      [:div.control
       [:a.button.is-light {:href "/todos"} "Reset"]]]]))

(defn preferences-form [request]
  (let [theme (user-theme request)
        density (user-density request)]
    [:form.box {:method "post" :action "/preferences"}
     (anti-forgery-field)
     [:h2.title.is-5 "Preferences"]
     [:div.field
      [:label.label {:for "pref-theme"} "Theme"]
      [:div.select.is-fullwidth
       [:select {:id "pref-theme" :name "prefs[theme]"}
        [:option {:value "default" :selected (= "default" theme)} "Default"]
        [:option {:value "dark" :selected (= "dark" theme)} "Dark"]]]]
     [:div.field
      [:label.label {:for "pref-density"} "Density"]
      [:div.select.is-fullwidth
       [:select {:id "pref-density" :name "prefs[density]"}
        [:option {:value "comfortable" :selected (= "comfortable" density)}
         "Comfortable"]
        [:option {:value "compact" :selected (= "compact" density)}
         "Compact"]]]]
     [:button.button.is-primary {:type "submit"} "Save"]]))

(defn upload-form []
  [:form.box {:method "post"
              :action "/imports"
              :enctype "multipart/form-data"}
   (anti-forgery-field)
   [:h2.title.is-5 "Import"]
   [:div.field
    [:label.label {:for "upload-note"} "Note"]
    [:input.input {:id "upload-note"
                   :type "text"
                   :name "upload[note]"
                   :placeholder "Optional note"}]]
   [:div.field
    [:label.label {:for "upload-file"} "File"]
    [:input {:id "upload-file"
             :type "file"
             :name "upload[file]"}]]
   [:button.button.is-link {:type "submit"} "Upload"]])

(defn upload-summary [request]
  (when-let [upload (get-in request [:session :last-upload])]
    [:div.message.is-link
     [:div.message-header "Last import"]
     [:div.message-body
      [:p [:strong (:filename upload)]]
      [:p (str (:size upload) " bytes, " (:content-type upload))]
      (when (seq (:note upload))
        [:p (:note upload)])]]))

(defn request-snapshot [request]
  [:div.box.request-snapshot
   [:h2.title.is-5 "Request"]
   [:div.columns
    [:div.column
     [:p [:strong "Method"] " " (name (:request-method request))]
     [:p [:strong "Scheme"] " " (name (:scheme request))]
     [:p [:strong "Remote"] " " (:remote-addr request)]]
    [:div.column
     [:p [:strong "Theme"] " " (user-theme request)]
     [:p [:strong "Density"] " " (user-density request)]
     [:p [:strong "Tags"] " " (pr-str (todo-tags request))]]]
   [:pre (pr-str {:params (:params request)
                  :query-params (:query-params request)
                  :form-params (:form-params request)
                  :multipart-params (:multipart-params request)
                  :cookies (keys (:cookies request))})]])

(defn todos-page [request]
  (let [user (:user request)
        todos (filtered-todos request)]
    (layout {:title "Todos"
             :user user
             :flash (:flash request)
             :theme (user-theme request)
             :density (user-density request)}
            [:div.notification.is-info.is-light
             "Signed in as " [:strong user]]
            (filter-form request)
            [:form.box.mb-5 {:method "post" :action "/todos"}
             (anti-forgery-field)
             [:label.label {:for "title"} "Add a todo"]
             [:div.field.has-addons
              [:div.control.is-expanded
               [:input.input {:id "title"
                              :type "text"
                              :name "todo[title]"
                              :placeholder "New todo"
                              :required true
                              :autofocus true}]]
              [:div.control
               [:button.button.is-primary {:type "submit"} "Add"]]]]
            [:div.columns
             [:div.column
              (preferences-form request)]
             [:div.column
              (upload-form)]]
            (upload-summary request)
            (if (seq todos)
              [:div (map todo-item todos)]
              [:div.notification.is-light "No todos matched."])
            (request-snapshot request))))

(defn root-handler [request]
  (if (current-user request)
    (redirect "/todos")
    (redirect "/login")))

(defn login-form-handler [request]
  (html-response (login-page request {})))

(defn login-handler [request]
  (let [params (:credentials (:params request))]
    (if (valid-credentials? params)
      (assoc (redirect "/todos")
             :session (assoc (:session request)
                             :user (:username params))
             :flash {:type :success
                     :text (str "Signed in as " (:username params))})
      (html-response
       (login-page request {:error "Invalid username or password"})
       401))))

(defn logout-handler [_request]
  (assoc (redirect "/login") :session nil))

(defn todos-handler [request]
  (authenticated request #(html-response (todos-page %))))

(defn add-todo-handler [request]
  (authenticated
   request
   (fn [request]
     (let [title (get-in request [:params :todo :title])]
       (when (seq (str/trim (or title "")))
         (let [id (swap! next-todo-id inc)]
           (swap! todos assoc id {:id id
                                  :title (str/trim title)
                                  :done? false}))))
     (assoc (redirect "/todos")
            :flash {:type :success
                    :text "Todo added"}))))

(defn preference-cookies [{:keys [theme density]}]
  {:todo-theme {:value (or theme "default")
                :path "/"
                :http-only true
                :same-site :lax}
   :todo-density {:value (or density "comfortable")
                  :path "/"
                  :http-only true
                  :same-site :lax}})

(defn preferences-handler [request]
  (authenticated
   request
   (fn [request]
     (let [prefs (:prefs (:params request))]
       (assoc (redirect "/todos")
              :cookies (preference-cookies prefs)
              :flash {:type :success
                      :text "Preferences saved"})))))

(defn upload-file-summary [file note]
  (if (map? file)
    {:filename (:filename file)
     :content-type (:content-type file)
     :size (:size file)
     :note note}
    {:filename "No file selected"
     :content-type "application/octet-stream"
     :size 0
     :note note}))

(defn imports-handler [request]
  (authenticated
   request
   (fn [request]
     (let [upload (:upload (:params request))
           summary (upload-file-summary (:file upload) (:note upload))]
       (assoc (redirect "/todos")
              :session (assoc (:session request) :last-upload summary)
              :flash {:type :info
                      :text (str "Import received: " (:filename summary))})))))

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
     (assoc (redirect "/todos")
            :flash {:type :success
                    :text "Todo updated"}))))

(defn delete-todo-handler [request]
  (authenticated
   request
   (fn [request]
     (swap! todos dissoc (todo-id request))
     (assoc (redirect "/todos")
            :flash {:type :warning
                    :text "Todo deleted"}))))

(defn health-handler [_request]
  {:status 200
   :headers {"ETag" health-etag
             "Last-Modified" health-last-modified}
   :body "ok\n"})

(defn default-handler [_request]
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not found\n"})

(def routes
  [{:pattern "/" :method :get :handler root-handler}
   {:pattern "/login" :method :get :handler login-form-handler}
   {:pattern "/login" :method :post :handler login-handler}
   {:pattern "/logout" :method :post :handler logout-handler}
   {:pattern "/todos" :method :get :handler todos-handler}
   {:pattern "/todos" :method :post :handler add-todo-handler}
   {:pattern "/preferences" :method :post :handler preferences-handler}
   {:pattern "/imports" :method :post :handler imports-handler}
   {:pattern "/todos/:id/toggle" :method :post :handler toggle-todo-handler}
   {:pattern "/todos/:id/delete" :method :post :handler delete-todo-handler}
   {:pattern "/health.txt" :handler health-handler}])

(def app-defaults
  (-> defaults/site-defaults
      (assoc-in [:session :cookie-name] session-cookie-name)
      (assoc-in [:security :absolute-redirects] true)
      (assoc-in [:security :proxy-headers] true)
      (assoc-in [:security :hsts]
                {:max-age 31536000
                 :include-subdomains? true})))

(defn wrap-route-defaults [route]
  (cond-> route
    (:handler route)
    (update :handler defaults/wrap-defaults app-defaults)

    (:async-handler route)
    (update :async-handler defaults/wrap-defaults app-defaults)))

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(def handler
  (routing/ring-routes (mapv wrap-route-defaults routes)
                       (defaults/wrap-defaults default-handler app-defaults)
                       request-options))

(defn main []
  (routing/run-adapter
   handler
   {:port (env-var "PORT")
    :hostname (or (env-var "HOSTNAME") "127.0.0.1")
    :onListen (fn [addr]
                (console/log
                 (str "Server running at "
                      (aget addr "hostname")
                      ":"
                      (aget addr "port"))))
    :reusePort (= "true" (env-var "REUSE_PORT"))}))

(set! *main-cli-fn* main)

(defn ^:export fetch [request]
  (handler request))

;; (def app
;;   (proxy
;;    {:handler handler
;;     :port (env-var "PORT")
;;     :hostname (or (env-var "HOSTNAME") "127.0.0.1")
;;     :onListen (fn [addr]
;;                 (console/log
;;                  (str "Server running at "
;;                       (aget addr "hostname")
;;                       ":"
;;                       (aget addr "port"))))
;;     :reusePort (= "true" (env-var "REUSE_PORT"))}))

;; (defn main []
;;   (routing/run-adapter app))

;; (set! *main-cli-fn* main)

;; (defn ^:export fetch [request]
;;   (handler request))
