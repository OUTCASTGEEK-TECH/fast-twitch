(ns fast-twitch.util.anti-forgery
  "View helpers for rendering anti-forgery form fields."
  [:require [fast-twitch.middlewares.anti-forgery :as anti-forgery]])

(defn anti-forgery-field
  "Returns a hidden form input populated with the current anti-forgery token."
  []
  [:input {:type "hidden"
           :id "__anti-forgery-token"
           :name "__anti-forgery-token"
           :value (force anti-forgery/*anti-forgery-token*)}])
