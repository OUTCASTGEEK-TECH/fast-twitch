(ns uploadapp.views)

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
      [:p.subtitle "Upload a file, inspect the request map data, and fetch a static file."]
      body]]]])

(defn upload-form []
  [:form.box {:method "post"
              :action "/upload"
              :enctype "multipart/form-data"}
   [:div.field
    [:label.label {:for "upload-note"} "Note"]
    [:div.control
     [:input.input {:id "upload-note"
                    :type "text"
                    :name "upload[note]"
                    :placeholder "Why are you uploading this file?"}]]]
   [:div.field
    [:label.label {:for "upload-file"} "File"]
    [:div.control
     [:input.input {:id "upload-file"
                    :type "file"
                    :name "upload[file]"}]]]
   [:div.field.is-grouped
    [:div.control
     [:button.button.is-link {:type "submit"} "Upload"]]
    [:div.control
     [:a.button.is-light {:href "/static/readme.txt"} "Open static file"]]]])

(defn home-page [settings]
  (layout
   settings
   [:div.columns
    [:section.column.is-two-thirds
     (upload-form)]
    [:aside.column
     [:div.notification.is-info.is-light
      [:p "The form field names are nested:"]
      [:pre "upload[note]\nupload[file]"]
      [:p "After middleware runs, the handler can read:"]
      [:pre "[:params :upload :file]"]]]]))

(defn upload-summary [summary]
  [:div.box
   [:h2.title.is-4 "Upload summary"]
   [:table.table.is-fullwidth
    [:tbody
     [:tr [:th "File name"] [:td (:filename summary)]]
     [:tr [:th "Browser content type"] [:td (:content-type summary)]]
     [:tr [:th "Size"] [:td (str (:size summary) " bytes")]]
     [:tr [:th "Note"] [:td (:note summary)]]]]
   [:p
    [:a.button.is-link {:href "/"} "Upload another file"]]])

(defn uploaded-page [settings summary]
  (layout
   settings
   (upload-summary summary)))

(defn not-found-page [settings uri]
  (layout
   settings
   [:div.notification.is-warning
    [:strong "No route matched "]
    [:code uri]]))
