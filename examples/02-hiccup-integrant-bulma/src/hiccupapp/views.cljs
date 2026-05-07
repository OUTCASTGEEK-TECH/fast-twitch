(ns hiccupapp.views)

(defn layout [{:keys [title bulma-css]} & body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title title]
    [:link {:rel "stylesheet" :href bulma-css}]]
   [:body
    [:section.hero.is-link
     [:div.hero-body
      [:div.container
       [:p.subtitle "fast-twitch newbie example"]
       [:h1.title title]]]]
    [:main.section
     [:div.container
      body]]]])

(defn home-page [settings]
  (layout
   settings
   [:div.columns
    [:section.column
     [:h2.title.is-4 "Hiccup is data"]
     [:div.box
      [:p "This box came from a vector in ClojureScript source code."]
      [:pre "[:div.box\n [:p \"This box came from a vector.\"]]"]]]
    [:section.column
     [:h2.title.is-4 "Integrant wires the parts"]
     [:div.box
      [:p "The config map builds settings, page handlers, routes, and the app."]
      [:a.button.is-link {:href "/hello/Newbie"} "Try /hello/Newbie"]]]]))

(defn hello-page [settings name]
  (layout
   settings
   [:div.box
    [:h2.title.is-3 (str "Hello, " name)]
    [:p "The name came from a path parameter in the request map."]
    [:p [:a {:href "/"} "Back home"]]]))

(defn not-found-page [settings uri]
  (layout
   settings
   [:div.notification.is-warning
    [:strong "No route matched "]
    [:code uri]]))
