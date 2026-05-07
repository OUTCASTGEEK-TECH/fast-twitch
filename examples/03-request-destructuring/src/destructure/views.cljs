(ns destructure.views)

(defn layout [{:keys [title bulma-css]} & body]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title title]
    [:link {:rel "stylesheet" :href bulma-css}]]
   [:body
    [:section.section
     [:div.container
      [:h1.title title]
      [:p.subtitle "Use these forms, then compare the response with the handler source."]
      body]]]])

(defn demo-nav []
  [:div.buttons
   [:a.button.is-light {:href "/"} "All forms"]
   [:a.button.is-light {:href "/hello/Ada?loud=true&topic=fast-twitch"}
    "GET /hello/Ada"]
   [:a.button.is-light {:href "/form"} "Form route"]
   [:a.button.is-light {:href "/profile"} "Profile route"]
   [:a.button.is-light {:href "/body-text"} "Body route"]])

(defn form-demo []
  [:section.box
   [:h2.title.is-4 "POST /form"]
   [:p "Repeated field names become a vector."]
   [:form {:method "post" :action "/form"}
    [:div.field
     [:label.label "Favorite colors"]
     [:label.checkbox
      [:input {:type "checkbox"
               :name "color"
               :value "blue"
               :checked true}]
      " blue"]
     " "
     [:label.checkbox
      [:input {:type "checkbox"
               :name "color"
               :value "green"
               :checked true}]
      " green"]]
    [:div.field
     [:label.label {:for "level"} "Level"]
     [:input.input {:id "level" :name "level" :value "beginner"}]]
    [:button.button.is-link {:type "submit"} "Submit form params"]]])

(defn profile-demo []
  [:section.box
   [:h2.title.is-4 "POST /profile"]
   [:p "Names like " [:code "user[name]"] " become nested maps."]
   [:form {:method "post" :action "/profile"}
    [:div.field
     [:label.label {:for "profile-name"} "Name"]
     [:input.input {:id "profile-name" :name "user[name]" :value "Ada"}]]
    [:div.field
     [:label.label {:for "profile-role"} "Role"]
     [:input.input {:id "profile-role" :name "user[role]" :value "newbie"}]]
    [:div.field
     [:label.label {:for "profile-language"} "Language"]
     [:input.input {:id "profile-language"
                    :name "user[language]"
                    :value "ClojureScript"}]]
    [:button.button.is-link {:type "submit"} "Submit nested params"]]])

(defn body-text-demo []
  [:section.box
   [:h2.title.is-4 "POST /body-text"]
   [:p "This form sends " [:code "text/plain"]
    ", so the async handler can read the raw body stream."]
   [:form {:method "post" :action "/body-text" :enctype "text/plain"}
    [:div.field
     [:label.label {:for "body-message"} "Message"]
     [:textarea.textarea
      {:id "body-message" :name "message"}
      "plain request bodies are streams until you read them"]]
    [:button.button.is-link {:type "submit"} "Submit raw body"]]])

(defn demo-page [settings active]
  (layout
   settings
   (demo-nav)
   (case active
     :form (form-demo)
     :profile (profile-demo)
     :body-text (body-text-demo)
     [:div
      (form-demo)
      (profile-demo)
      (body-text-demo)])))
