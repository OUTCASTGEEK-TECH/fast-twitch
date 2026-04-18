(ns ddc.server
  [:require-macros [fast-twitch.macros :refer [env-var]]]
  [:require
   ;; [cljs.proxy :refer [builder]]
   [clojure.string :as str]
   [fast-twitch.macros]
   [fast-twitch.middlewares.content-type :as content-type]
   [fast-twitch.middlewares.defaults :as defaults]
   [fast-twitch.middlewares.file :as file]
   [fast-twitch.routing :as routing]
   [fast-twitch.util.anti-forgery :refer [anti-forgery-field]]
   [replicant.string :as html]]
  [:refer-global :only [Number console globalThis]])

(defonce todos
  (atom {1 {:id 1 :title "Wire ft request maps" :done? true}
         2 {:id 2 :title "Render todos with Replicant" :done? false}}))

(defonce next-todo-id (atom 2))

(def session-cookie-name "ddc_session")

(def health-etag "W/\"ddc-health-v1\"")

(def health-last-modified "Fri, 17 Apr 2026 00:00:00 GMT")

(defn todo-list []
  (sort-by :id (vals @todos)))

(defn cookie-value [cookies k]
  (get-in cookies [k :value]))

(defn user-theme [cookies]
  (or (cookie-value cookies :todo-theme) "default"))

(defn user-density [cookies]
  (or (cookie-value cookies :todo-density) "comfortable"))

(defn todo-filters [{:keys [params session]}]
  (or (:filters params)
      (:last-filters session)
      {}))

(defn todo-tags [{:keys [params query-params]}]
  (let [tags (or (:tags params)
                 (:tags query-params))]
    (cond
      (nil? tags) []
      (sequential? tags) (vec (flatten tags))
      :else [tags])))

(defn page-context [{:keys [cookies flash user]}]
  {:flash flash
   :theme (user-theme cookies)
   :density (user-density cookies)
   :user user})

(defn flash-class [type]
  (case type
    :success "is-success"
    :warning "is-warning"
    :danger "is-danger"
    :info "is-info"
    "is-info"))

(defn flash-view [flash]
  (when flash
    [:div.system-flash
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

(defn internal-location
  ([location]
   (internal-location location "/todos"))
  ([location fallback]
   (if (and (string? location)
            (str/starts-with? location "/")
            (not (str/starts-with? location "//")))
     location
     fallback)))

(defn current-user [{:keys [session]}]
  (:user session))

(defn authenticated [request handler]
  (if-let [user (current-user request)]
    (handler (assoc request :user user))
    (redirect "/login")))

(defn valid-credentials? [{:keys [username password]}]
  (and (= username (or (env-var "TODO_USER") "admin"))
       (= password (or (env-var "TODO_PASSWORD") "password"))))

(defn page-path [active]
  (case active
    :imports "/imports"
    :health "/health"
    :settings "/settings"
    "/todos"))

(defn active-nav-class [active page]
  (when (= active page) "is-active"))

(defn nav-link [active page href label pill]
  [:a.rail-nav-item
   {:href href
    :class (active-nav-class active page)}
   [:span label]
   [:span.rail-pill pill]])

(defn next-theme [theme]
  (if (= theme "dark") "default" "dark"))

(defn theme-toggle-icon [theme]
  (if (= theme "dark")
    [:svg.theme-toggle-icon
     {:viewBox "0 0 24 24"
      :aria-hidden "true"}
     [:path {:d "M12 4.5a1 1 0 0 1 1 1V7a1 1 0 1 1-2 0V5.5a1 1 0 0 1 1-1Zm0 11.5a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm6.5-5a1 1 0 0 1 1 1 1 1 0 0 1-1 1H17a1 1 0 1 1 0-2h1.5ZM7 12a1 1 0 0 1-1 1H4.5a1 1 0 1 1 0-2H6a1 1 0 0 1 1 1Zm9.95-6.36a1 1 0 0 1 1.41 1.41l-1.06 1.06a1 1 0 0 1-1.42-1.41l1.07-1.06ZM8.12 15.88a1 1 0 0 1 0 1.42l-1.06 1.06a1 1 0 0 1-1.42-1.41l1.06-1.07a1 1 0 0 1 1.42 0Zm10.24 1.07a1 1 0 0 1-1.41 1.41l-1.07-1.06a1 1 0 0 1 1.42-1.42l1.06 1.07ZM8.12 8.12a1 1 0 0 1-1.42 0L5.64 7.05a1 1 0 0 1 1.42-1.41L8.12 6.7a1 1 0 0 1 0 1.42Z"}]]
    [:svg.theme-toggle-icon
     {:viewBox "0 0 24 24"
      :aria-hidden "true"}
     [:path {:d "M20.3 14.3a8 8 0 0 1-10.6-10.6 1 1 0 0 0-1.12-1.47A10 10 0 1 0 21.77 15.4a1 1 0 0 0-1.47-1.1ZM12 20a8 8 0 0 1-6.58-12.55 10 10 0 0 0 11.13 11.13A7.96 7.96 0 0 1 12 20Z"}]]))

(defn theme-toggle-form [{:keys [theme density return-to compact?]}]
  (let [next (next-theme theme)]
    [:form.theme-toggle-form {:method "post" :action "/preferences"}
     (anti-forgery-field)
     [:input {:type "hidden" :name "prefs[theme]" :value next}]
     [:input {:type "hidden" :name "prefs[density]" :value density}]
     [:input {:type "hidden" :name "prefs[return-to]" :value return-to}]
     [:button.theme-toggle-button
      {:type "submit"
       :aria-label (str "Switch to "
                        (if (= next "dark") "dark" "light")
                        " theme")}
      (theme-toggle-icon theme)
      (when-not compact?
        [:span (if (= next "dark") "Dark" "Light")])]]))

(defn layout [{:keys [title subtitle active user flash theme density]} & body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title title]
    [:link {:rel "stylesheet"
            :href "https://cdn.jsdelivr.net/npm/bulma@1.0.4/css/bulma.min.css"}]
    [:link {:rel "stylesheet"
            :href "/static/css/app.css"}]]
   [:body
    {:class [(str "theme-" theme)
             (str "density-" density)]}
    (if user
      [:div.app-shell
       [:aside.app-rail
        [:div.rail-brand
         [:p.eyebrow "data-driven-clarity"]
         [:h1 "Operator console"]]
        [:div.rail-status
         [:span "Signed in"]
         [:strong user]]
        [:nav.rail-nav
         (nav-link active :todos "/todos" "Todos" "Work")
         (nav-link active :imports "/imports" "Imports" "MIME")
         (nav-link active :health "/health" "Health" "OK")
         (nav-link active :settings "/settings" "Settings" "UI")]
        [:div.rail-footer.rail-actions
         (theme-toggle-form {:theme theme
                             :density density
                             :return-to (page-path active)})
         [:form {:method "post" :action "/logout"}
          (anti-forgery-field)
          [:button.button {:type "submit"} "Sign out"]]]]
       [:main.app-main
        [:header.workspace-topbar
         [:div
          [:p.workspace-eyebrow "Authenticated workspace"]
          [:h2.workspace-title title]
          [:p.workspace-subtitle subtitle]]]
        (flash-view flash)
        body]]
      [:main.auth-shell
       body])]])

(defn login-page [{:keys [flash theme density error]}]
  (layout {:title "Sign in"
           :flash flash
           :theme theme
           :density density}
          [:section.auth-panel
           [:p.panel-kicker "data-driven-clarity"]
           [:h1 "Sign in"]
           [:p "Access the operator console for todos, imports, preferences, and request diagnostics."]
           (when error
             [:div.system-flash.is-danger error])
           [:form.ops-form {:method "post" :action "/login"}
            (anti-forgery-field)
            [:div.field
             [:label.field-label {:for "username"} "User"]
             [:input.input {:id "username"
                            :type "text"
                            :name "credentials[username]"
                            :autocomplete "username"
                            :autofocus true}]]
            [:div.field
             [:label.field-label {:for "password"} "Password"]
             [:input.input {:id "password"
                            :type "password"
                            :name "credentials[password]"
                            :autocomplete "current-password"}]]
            [:button.button.is-primary.is-fullwidth {:type "submit"}
             "Sign in"]]]))

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

(defn filtered-todos [filters]
  (filter #(todo-matches? filters %) (todo-list)))

(defn todo-stats []
  (let [items (todo-list)
        done (count (filter :done? items))
        total (count items)]
    {:total total
     :done done
     :open (- total done)}))

(defn status-label [status]
  (case status
    "open" "Open"
    "done" "Done"
    "All"))

(defn stats-strip [{:keys [total open done]}]
  [:div.stats-strip
   [:div.stat-tile
    [:strong total]
    [:span "Total"]]
   [:div.stat-tile
    [:strong open]
    [:span "Open"]]
   [:div.stat-tile
    [:strong done]
    [:span "Done"]]])

(defn todo-item [{:keys [id title done?]}]
  [:article.task-row
   {:class (if done? "is-complete" "is-open")}
   [:form {:method "post" :action (str "/todos/" id "/toggle")}
    (anti-forgery-field)
    [:button.button.is-small.task-status-button
     {:class (if done? "is-light" "is-link")
      :type "submit"}
     (if done? "Undo" "Done")]]
   [:div
    [:div.task-title title]
    [:div.task-meta
     [:span.meta-pill (str "#" id)]
     [:span.meta-pill {:class (if done? "is-done" "is-open")}
      (if done? "Done" "Open")]
     [:span.meta-pill "ft"]]]
   [:form {:method "post" :action (str "/todos/" id "/delete")}
    (anti-forgery-field)
    [:button.button.is-small.delete-button {:type "submit"}
     "Delete"]]])

(defn filter-form [{:keys [filters tags]}]
  (let [{:keys [status q]} filters
        tags (set tags)]
    [:form.todo-filter-bar {:method "get" :action "/todos"}
     [:div.todo-filter-search
      [:label.visually-hidden {:for "filter-q"} "Search"]
      [:input.input {:id "filter-q"
                     :type "search"
                     :name "filters[q]"
                     :value q
                     :placeholder "Search todos"}]]
     [:div.todo-filter-status
      [:label.visually-hidden {:for "filter-status"} "Status"]
      [:div.select.is-fullwidth
       [:select {:id "filter-status" :name "filters[status]"}
        [:option {:value "" :selected (or (nil? status) (= "" status))} "All"]
        [:option {:value "open" :selected (= "open" status)} "Open"]
        [:option {:value "done" :selected (= "done" status)} "Done"]]]]
     [:div.compact-tags
      [:label.filter-chip
       {:class (when (contains? tags "cljs") "is-active")}
       [:input {:type "checkbox"
                :name "tags[]"
                :value "cljs"
                :checked (contains? tags "cljs")}]
       [:span "CLJS"]]
      [:label.filter-chip
       {:class (when (contains? tags "ft") "is-active")}
       [:input {:type "checkbox"
                :name "tags[]"
                :value "ft"
                :checked (contains? tags "ft")}]
       [:span "ft"]]
      [:label.filter-chip
       {:class (when (contains? tags "std") "is-active")}
       [:input {:type "checkbox"
                :name "tags[]"
                :value "std"
                :checked (contains? tags "std")}]
       [:span "@std"]]]
     [:div.todo-filter-actions
      [:span.active-filter (status-label status)]
      [:button.button.is-link {:type "submit"} "Apply"]
      [:a.button.is-light {:href "/todos"} "Reset"]]]))

(defn preferences-form [{:keys [theme density return-to]}]
  [:section.surface-paper
   [:div.panel-heading
    [:div
     [:p.panel-kicker "Workspace"]
     [:h3 "Appearance"]]]
   [:div.ops-form
    [:div.field
     [:label.field-label "Theme"]
     (theme-toggle-form {:theme theme
                         :density density
                         :return-to return-to})]]
   [:form.ops-form {:method "post" :action "/preferences"}
    (anti-forgery-field)
    [:input {:type "hidden" :name "prefs[theme]" :value theme}]
    [:input {:type "hidden" :name "prefs[return-to]" :value return-to}]
    [:div.field
     [:label.field-label {:for "pref-density"} "Density"]
     [:div.select.is-fullwidth
      [:select {:id "pref-density" :name "prefs[density]"}
       [:option {:value "comfortable" :selected (= "comfortable" density)}
        "Comfortable"]
       [:option {:value "compact" :selected (= "compact" density)}
        "Compact"]]]]
    [:button.button.is-primary {:type "submit"} "Save density"]]])

(defn upload-form []
  [:section.ops-panel
   [:div.panel-heading
    [:div
     [:p.panel-kicker "Import"]
     [:h3 "MIME validation"]]]
   [:form.ops-form {:method "post"
                    :action "/imports"
                    :enctype "multipart/form-data"}
    (anti-forgery-field)
    [:div.field
     [:label.field-label {:for "upload-note"} "Note"]
     [:textarea.textarea {:id "upload-note"
                          :name "upload[note]"
                          :rows 3
                          :placeholder "Why is this file being imported?"}]]
    [:div.field
     [:label.field-label {:for "upload-file"} "File"]
     [:input.file-input-native {:id "upload-file"
                                :type "file"
                                :name "upload[file]"}]]
    [:button.button.is-link {:type "submit"} "Upload and inspect"]]])

(defn upload-summary [upload]
  (when upload
    [:section.import-summary
     [:div.panel-heading
      [:div
       [:p.panel-kicker "Last import"]
       [:h3 (:filename upload)]]
      [:span.meta-pill (str (:size upload) " bytes")]]
     [:div.summary-grid
      [:div.summary-cell
       [:span "Declared"]
       [:code (:declared-content-type upload)]]
      [:div.summary-cell
       [:span "Expected"]
       [:code (:expected-content-type upload)]]
      [:div.summary-cell
       [:span "Sniffed"]
       [:code (:sniffed-content-type upload)]]
      [:div.summary-cell
       [:span "Note"]
       [:code (or (:note upload) "none")]]]
     (when-let [warning (:content-type-warning upload)]
       [:p.warning-copy warning])]))

(defn request-snapshot [{:keys [request-method scheme remote-addr theme density
                                tags params query-params form-params
                                multipart-params cookies]}]
  [:section.request-snapshot
   [:h2 "Request diagnostics"]
   [:div.request-grid
    [:p [:strong "Method"] " " (name request-method)]
    [:p [:strong "Scheme"] " " (name scheme)]
    [:p [:strong "Remote"] " " remote-addr]
    [:p [:strong "Theme"] " " theme]
    [:p [:strong "Density"] " " density]
    [:p [:strong "Tags"] " " (pr-str tags)]]
   [:pre (pr-str {:params params
                  :query-params query-params
                  :form-params form-params
                  :multipart-params multipart-params
                  :cookies (keys cookies)})]])

(defn todos-page [request]
  (let [context (page-context request)
        filters (todo-filters request)
        tags (todo-tags request)
        todos (filtered-todos filters)
        stats (todo-stats)]
    (layout {:title "Todos"
             :subtitle "A focused task queue: create, filter, complete, and delete todos without operational clutter."
             :active :todos
             :user (:user context)
             :flash (:flash context)
             :theme (:theme context)
             :density (:density context)}
            [:section.content-stack
             (stats-strip stats)
             [:section.surface-paper
              [:div.panel-heading
               [:div
                [:p.panel-kicker "Create"]
                [:h2 "Add a todo"]]
               [:span.panel-note "POST /todos"]]
              [:form.composer-form {:method "post" :action "/todos"}
               (anti-forgery-field)
               [:label.visually-hidden {:for "title"} "Todo title"]
               [:input.input {:id "title"
                              :type "text"
                              :name "todo[title]"
                              :placeholder "Describe the next task"
                              :required true
                              :autofocus true}]
               [:button.button.is-primary {:type "submit"} "Add"]]]
             (filter-form {:filters filters :tags tags})
             [:section.surface-panel
              [:div.panel-heading
               [:div
                [:p.panel-kicker "Queue"]
                [:h2 "Task list"]]
               [:span.panel-note (str (status-label (:status filters)) " view")]]
             (if (seq todos)
                [:div.task-list (map todo-item todos)]
                [:div.empty-state
                 [:strong "No matching tasks"]
                 [:p "Adjust the filters or add a task to populate this view."]])]])))

(defn imports-page [{:keys [session] :as request}]
  (let [context (page-context request)]
    (layout {:title "Imports"
             :subtitle "Upload a file, compare declared content type with extension lookup, and inspect the sniffed resource header."
             :active :imports
             :user (:user context)
             :flash (:flash context)
             :theme (:theme context)
             :density (:density context)}
            [:section.content-stack
             (upload-form)
             (if-let [upload (:last-upload session)]
               (upload-summary upload)
               [:div.empty-state
                [:strong "No import inspected yet"]
                [:p "Upload a file to see declared, expected, and sniffed content types."]])])))

(defn settings-page [request]
  (let [context (page-context request)]
    (layout {:title "Settings"
             :subtitle "Adjust presentation preferences for the server-rendered workspace."
             :active :settings
             :user (:user context)
             :flash (:flash context)
             :theme (:theme context)
             :density (:density context)}
            [:section.content-stack
             (preferences-form {:theme (:theme context)
                                :density (:density context)
                                :return-to "/settings"})])))

(defn health-page [{:keys [request-method scheme remote-addr params
                           query-params form-params multipart-params cookies]
                    :as request}]
  (let [context (page-context request)]
    (layout {:title "Health"
             :subtitle "Human-readable service health with the raw text endpoint still available for scripts."
             :active :health
             :user (:user context)
             :flash (:flash context)
             :theme (:theme context)
             :density (:density context)}
            [:section.content-wide
             [:section.surface-panel
              [:div.panel-heading
               [:div
                [:p.panel-kicker "Service"]
                [:h2 "Runtime status"]]
               [:span.status-dot "Operational"]]
              [:div.health-grid
               [:div.health-tile
                [:strong "ok"]
                [:span "Application handler responded"]]
               [:div.health-tile
                [:strong health-etag]
                [:span "Current ETag"]]
               [:div.health-tile
                [:strong health-last-modified]
                [:span "Last modified"]]
               [:div.health-tile
                [:strong (name request-method)]
                [:span "Request method"]]
               [:div.health-tile
                [:strong (name scheme)]
                [:span "Scheme"]]
               [:div.health-tile
                [:strong remote-addr]
                [:span "Remote address"]]]]
             [:section.surface-paper
              [:div.panel-heading
               [:div
                [:p.panel-kicker "Machine endpoint"]
                [:h2 "Plain health check"]]
               [:a.button.is-light {:href "/health.txt"} "/health.txt"]]
              [:p.panel-note
               "The text endpoint remains available for curl, uptime probes, and adapters that expect a small body."]]
             (request-snapshot {:request-method request-method
                                :scheme scheme
                                :remote-addr remote-addr
                                :theme (:theme context)
                                :density (:density context)
                                :tags []
                                :params params
                                :query-params query-params
                                :form-params form-params
                                :multipart-params multipart-params
                                :cookies cookies})])))

(defn root-handler [request]
  (if (current-user request)
    (redirect "/todos")
    (redirect "/login")))

(defn login-form-handler [{:keys [cookies flash]}]
  (html-response
   (login-page (assoc (page-context {:cookies cookies :flash flash})
                      :error nil))))

(defn login-handler [{:keys [cookies flash params session]}]
  (let [params (:credentials params)]
    (if (valid-credentials? params)
      (assoc (redirect "/todos")
             :session (assoc session
                             :user (:username params))
             :flash {:type :success
                     :text (str "Signed in as " (:username params))})
      (html-response
       (login-page (assoc (page-context {:cookies cookies :flash flash})
                          :error "Invalid username or password"))
       401))))

(defn logout-handler [_request]
  (assoc (redirect "/login") :session nil))

(defn todos-handler [request]
  (authenticated request #(html-response (todos-page %))))

(defn imports-page-handler [request]
  (authenticated request #(html-response (imports-page %))))

(defn settings-handler [request]
  (authenticated request #(html-response (settings-page %))))

(defn health-page-handler [request]
  (authenticated request #(html-response (health-page %))))

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

(defn preferences-handler [{:keys [params] :as request}]
  (authenticated
   request
   (fn [_request]
     (let [prefs (:prefs params)
           return-to (internal-location (:return-to prefs) "/settings")]
       (assoc (redirect return-to)
              :cookies (preference-cookies prefs)
              :flash {:type :success
                      :text "Preferences saved"})))))

(defn upload-file-summary [file note]
  (-> (content-type/file-content-type-summary file)
      (.then #(assoc % :note note))))

(defn imports-handler [{:keys [params session] :as request} respond raise]
  (if (current-user request)
    (let [upload (:upload params)]
      (-> (upload-file-summary (:file upload) (:note upload))
          (.then (fn [summary]
                   (respond
                    (assoc (redirect "/imports")
                           :session (assoc session :last-upload summary)
                           :flash {:type :info
                                   :text (str "Import received: "
                                              (:filename summary))}))))
          (.catch raise)))
    (respond (redirect "/login"))))

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
   {:pattern "/imports" :method :get :handler imports-page-handler}
   {:pattern "/preferences" :method :post :handler preferences-handler}
   {:pattern "/imports" :method :post :async-handler imports-handler}
   {:pattern "/settings" :method :get :handler settings-handler}
   {:pattern "/health" :method :get :handler health-page-handler}
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

(def request-options
  {:protocol "HTTP/1.1"
   :remote-addr "127.0.0.1"})

(def app
  (-> (routing/routes routes default-handler)
      (file/wrap-file "static" {:url-root "static"})
      (defaults/wrap-defaults app-defaults)))

(def handler
  (routing/ft-handler app request-options))

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

;; (def proxy (builder))

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
