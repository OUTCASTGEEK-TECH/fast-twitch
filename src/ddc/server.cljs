(ns ddc.server
  [:require-macros [ddc.macros :refer [env-var]]]
  [:require
   ;; [cljs.proxy :refer [builder]]
   [clojure.string :as str]
   [ddc.macros]
   [ddc.middlewares.content-type :as content-type]
   [ddc.middlewares.defaults :as defaults]
   [ddc.routing :as routing]
   [ddc.util.anti-forgery :refer [anti-forgery-field]]
   [replicant.string :as html]]
  [:refer-global :only [Number console globalThis]])

(defonce todos
  (atom {1 {:id 1 :title "Wire Ring request maps" :done? true}
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
    [:style
     ":root{--bg:#f6fafe;--surface:#e7eff5;--surface-low:#eef4fa;--surface-high:#d9e4ec;--paper:#fff;--ink:#2a343a;--muted:#566167;--line:rgba(114,124,131,.22);--primary:#5148d8;--primary-strong:#4338ca;--teal:#006b62;--teal-soft:#dffbf6;--danger:#a8364b;--danger-soft:#fff0f3;--rail:#0a0f12;--rail-muted:#999da1}
      html,body{min-height:100%;background:var(--bg);color:var(--ink)}
      body{font-size:15px}
      button,input,select,textarea{font:inherit}
      a,button,input,select,textarea{border-radius:8px}
      a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--primary);outline-offset:2px}
      .app-shell{min-height:100vh;display:grid;grid-template-columns:280px minmax(0,1fr);background:var(--bg)}
      .app-rail{background:var(--rail);color:#f6fafe;padding:28px 24px;display:flex;flex-direction:column;gap:24px;position:sticky;top:0;height:100vh}
      .rail-brand .eyebrow,.workspace-eyebrow,.panel-kicker{font-size:.72rem;text-transform:uppercase;letter-spacing:.08em;font-weight:700;color:var(--muted)}
      .rail-brand h1{font-size:1.35rem;line-height:1.1;font-weight:800;color:#fff;margin:.35rem 0 0}
      .rail-status{background:rgba(255,255,255,.06);border-radius:8px;padding:14px}
      .rail-status strong{display:block;color:#fff;font-size:.95rem;overflow-wrap:anywhere}
      .rail-status span{color:var(--rail-muted);font-size:.82rem}
      .rail-nav{display:grid;gap:8px}
      .rail-nav-item{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;border-radius:8px;color:#dce6ec;background:rgba(255,255,255,.04)}
      .rail-nav-item.is-active{background:#eef4fa;color:#172026;font-weight:700}
      .rail-pill{font-size:.72rem;color:#0a0f12;background:#89f5e7;border-radius:999px;padding:2px 8px}
      .rail-footer{margin-top:auto}
      .rail-footer .button{width:100%;background:#f6fafe;color:#0a0f12;border:0;font-weight:700}
      .rail-actions{display:grid;gap:10px}
      .theme-toggle-form{margin:0}
      .theme-toggle-button{width:100%;display:inline-flex;align-items:center;justify-content:center;gap:8px;background:var(--surface-high);color:var(--ink);border:0;border-radius:8px;padding:10px 12px;font-weight:800;cursor:pointer}
      .app-rail .theme-toggle-button{background:rgba(255,255,255,.08);color:#f6fafe}
      .theme-toggle-icon{width:18px;height:18px;display:block;fill:currentColor;flex:none}
      .app-main{padding:28px;min-width:0}
      .workspace-topbar{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;margin-bottom:18px}
      .workspace-title{font-size:1.85rem;line-height:1;font-weight:800;margin:.2rem 0 .45rem;color:var(--ink)}
      .workspace-subtitle{color:var(--muted);max-width:64ch}
      .stats-strip{display:grid;grid-template-columns:repeat(3,minmax(90px,1fr));gap:8px;min-width:310px}
      .stat-tile{background:var(--surface-low);border-radius:8px;padding:12px}
      .stat-tile strong{display:block;font-size:1.35rem;line-height:1;color:var(--ink)}
      .stat-tile span{font-size:.75rem;color:var(--muted);text-transform:uppercase;letter-spacing:.06em;font-weight:700}
      .workspace-grid{display:grid;grid-template-columns:minmax(0,1fr) 360px;gap:18px;align-items:start}
      .content-stack{display:grid;gap:14px;max-width:980px}
      .content-wide{display:grid;gap:14px}
      .workspace-main,.ops-rail{display:grid;gap:14px}
      .surface-panel{background:var(--surface);border-radius:8px;padding:14px}
      .surface-paper{background:var(--paper);border-radius:8px;padding:14px;box-shadow:0 18px 42px rgba(42,52,58,.06)}
      .panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
      .panel-heading h2,.panel-heading h3{font-size:.95rem;font-weight:800;color:var(--ink);margin:0}
      .panel-note{font-size:.82rem;color:var(--muted)}
      .system-flash{border-radius:8px;padding:12px 14px;margin-bottom:16px;font-weight:700;background:#e8f4ff;color:#0f3b57}
      .system-flash.is-success{background:#dffbf6;color:#004841}
      .system-flash.is-warning{background:#fff7df;color:#6b4b00}
      .system-flash.is-danger{background:var(--danger-soft);color:var(--danger)}
      .composer-form{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px}
      .input,.select select,.textarea{background:var(--paper);border:1px solid var(--line);box-shadow:none;color:var(--ink)}
      .input::placeholder,.textarea::placeholder{color:#8a949b}
      .button.is-primary,.button.is-link{background:var(--primary);border-color:var(--primary);color:#fff;font-weight:800}
      .button.is-primary:hover,.button.is-link:hover{background:var(--primary-strong);border-color:var(--primary-strong);color:#fff}
      .button.is-light{background:var(--surface-high);border-color:transparent;color:var(--ink);font-weight:700}
      .todo-filter-bar{display:grid;grid-template-columns:minmax(180px,1fr) 140px auto auto;gap:8px;align-items:center}
      .todo-filter-search .input,.todo-filter-status select,.todo-filter-actions .button{height:36px}
      .compact-tags{display:flex;gap:6px;flex-wrap:wrap}
      .filter-chip{position:relative;display:inline-flex;align-items:center;gap:6px;padding:7px 10px;border-radius:999px;background:var(--surface-low);color:var(--muted);font-weight:700;cursor:pointer}
      .filter-chip input{position:absolute;opacity:0;pointer-events:none}
      .filter-chip.is-active{background:#e6e4ff;color:var(--primary-strong)}
      .todo-filter-actions{display:flex;gap:6px;align-items:center;justify-content:flex-end}
      .active-filter{font-size:.78rem;font-weight:800;color:var(--primary-strong);background:#e6e4ff;border-radius:999px;padding:5px 9px}
      .visually-hidden{position:absolute!important;width:1px!important;height:1px!important;padding:0!important;margin:-1px!important;overflow:hidden!important;clip:rect(0,0,0,0)!important;white-space:nowrap!important;border:0!important}
      .task-list{display:grid;gap:10px}
      .task-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:12px;align-items:center;background:var(--paper);border-radius:8px;padding:12px;box-shadow:0 10px 28px rgba(42,52,58,.05)}
      .task-row.is-complete{background:#fbfcfd}
      .task-status-button{min-width:76px}
      .task-title{font-weight:800;color:var(--ink);overflow-wrap:anywhere}
      .task-row.is-complete .task-title{text-decoration:line-through;color:#6f7a82}
      .task-meta{display:flex;gap:8px;flex-wrap:wrap;margin-top:5px}
      .meta-pill{font-size:.72rem;font-weight:800;border-radius:999px;padding:3px 8px;background:var(--surface-low);color:var(--muted)}
      .meta-pill.is-done{background:var(--teal-soft);color:var(--teal)}
      .meta-pill.is-open{background:#e6e4ff;color:var(--primary-strong)}
      .delete-button{background:var(--danger-soft);color:var(--danger);border:0}
      .empty-state{background:var(--paper);border-radius:8px;padding:24px;text-align:center;color:var(--muted)}
      .ops-panel{background:var(--surface-low);border-radius:8px;padding:14px}
      .ops-form{display:grid;gap:12px}
      .field-label{display:block;font-size:.78rem;text-transform:uppercase;letter-spacing:.06em;font-weight:800;color:var(--muted);margin-bottom:6px}
      .file-input-native{width:100%;background:var(--paper);border:1px solid var(--line);border-radius:8px;padding:8px;color:var(--muted)}
      .import-summary{background:#fff8e8;border-radius:8px;padding:14px;color:#604400}
      .import-summary strong{color:#3e2b00}
      .summary-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px}
      .summary-cell{background:rgba(255,255,255,.65);border-radius:8px;padding:8px}
      .summary-cell span{display:block;font-size:.7rem;text-transform:uppercase;letter-spacing:.06em;font-weight:800;color:#806115}
      .summary-cell code{font-size:.82rem;color:#2a343a;overflow-wrap:anywhere}
      .warning-copy{margin-top:10px;font-weight:800;color:#7a5400}
      .request-snapshot{background:#0a0f12;color:#eff6fb;border-radius:8px;padding:14px}
      .request-snapshot h2{font-size:.9rem;font-weight:800;color:#fff;margin:0 0 10px}
      .request-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px}
      .request-grid p{margin:0;font-size:.82rem;color:#b8c3ca}
      .request-grid strong{color:#fff}
      .request-snapshot pre{white-space:pre-wrap;word-break:break-word;background:#12191f;color:#d6f7ee;border-radius:8px;padding:12px;font-size:.78rem;line-height:1.55}
      .health-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
      .health-tile{background:var(--paper);border-radius:8px;padding:14px;box-shadow:0 12px 32px rgba(42,52,58,.05)}
      .health-tile strong{display:block;font-size:1.1rem;color:var(--ink)}
      .health-tile span{display:block;margin-top:4px;color:var(--muted);font-size:.82rem}
      .status-dot{display:inline-flex;align-items:center;gap:8px;font-weight:800;color:var(--teal)}
      .status-dot::before{content:\"\";width:10px;height:10px;border-radius:999px;background:var(--teal);display:inline-block}
      .auth-shell{min-height:100vh;display:grid;place-items:center;background:var(--bg);padding:24px}
      .auth-panel{width:min(440px,100%);background:#fff;border-radius:8px;padding:28px;box-shadow:0 24px 64px rgba(42,52,58,.1)}
      .auth-panel h1{font-size:1.7rem;font-weight:800;margin:0 0 8px;color:var(--ink)}
      .auth-panel p{color:var(--muted);margin-bottom:20px}
      .density-comfortable{font-size:15px}
      .density-comfortable .app-main{padding:28px}
      .density-comfortable .app-rail{padding:28px 24px;gap:24px}
      .density-comfortable .content-stack,.density-comfortable .content-wide,.density-comfortable .workspace-main,.density-comfortable .ops-rail{gap:14px}
      .density-comfortable .surface-paper,.density-comfortable .surface-panel,.density-comfortable .ops-panel{padding:14px}
      .density-comfortable .task-row{padding:12px;gap:12px}
      .density-comfortable .stat-tile{padding:12px}
      .density-compact{font-size:14px}
      .density-compact .app-main{padding:18px}
      .density-compact .app-rail{padding:20px;gap:16px}
      .density-compact .rail-status{padding:10px}
      .density-compact .rail-nav-item{padding:8px 10px}
      .density-compact .workspace-topbar{margin-bottom:12px}
      .density-compact .workspace-title{font-size:1.55rem}
      .density-compact .stats-strip{gap:6px}
      .density-compact .stat-tile{padding:8px 10px}
      .density-compact .stat-tile strong{font-size:1.12rem}
      .density-compact .content-stack,.density-compact .content-wide,.density-compact .workspace-main,.density-compact .ops-rail{gap:10px}
      .density-compact .surface-paper,.density-compact .surface-panel,.density-compact .ops-panel{padding:10px}
      .density-compact .panel-heading{margin-bottom:8px}
      .density-compact .composer-form{gap:8px}
      .density-compact .input,.density-compact .select select,.density-compact .textarea{font-size:.9rem}
      .density-compact .todo-filter-bar{gap:6px}
      .density-compact .todo-filter-search .input,.density-compact .todo-filter-status select,.density-compact .todo-filter-actions .button{height:32px}
      .density-compact .filter-chip{padding:5px 8px;font-size:.78rem}
      .density-compact .task-list{gap:7px}
      .density-compact .task-row{padding:8px 10px;gap:8px}
      .density-compact .task-meta{margin-top:3px}
      .theme-dark{--bg:#111820;--surface:#17212a;--surface-low:#1d2933;--surface-high:#263541;--paper:#10171d;--ink:#f1f7fb;--muted:#aab6bf;--line:rgba(214,231,241,.16)}
      .theme-dark .auth-shell{background:#111820}
      .theme-dark .input,.theme-dark .select select,.theme-dark .textarea,.theme-dark .file-input-native{background:#10171d;color:#f1f7fb}
      @media (max-width:1080px){.app-shell{grid-template-columns:1fr}.app-rail{position:static;height:auto}.workspace-grid{grid-template-columns:1fr}.workspace-topbar{display:grid}.stats-strip{min-width:0}}
      @media (max-width:680px){.app-main{padding:18px}.todo-filter-bar,.composer-form,.stats-strip,.health-grid{grid-template-columns:1fr}.task-row{grid-template-columns:1fr}.todo-filter-actions{justify-content:flex-start}.workspace-title{font-size:1.45rem}}"]]
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
     [:span.meta-pill "Ring"]]]
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
       {:class (when (contains? tags "ring") "is-active")}
       [:input {:type "checkbox"
                :name "tags[]"
                :value "ring"
                :checked (contains? tags "ring")}]
       [:span "Ring"]]
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
