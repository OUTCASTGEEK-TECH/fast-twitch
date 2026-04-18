(ns fast-twitch.util.anti-forgery
  "View helpers for rendering anti-forgery form fields."
  [:require [fast-twitch.middlewares.anti-forgery :as anti-forgery]])

(defn anti-forgery-field
  "Returns a hidden form input populated with the current anti-forgery token."
  ([]
   (anti-forgery-field {}))
  ([options]
   (let [param-name (or (:param-name options)
                        (force anti-forgery/*anti-forgery-param-name*)
                        anti-forgery/default-token-param-name)]
     [:input {:type "hidden"
              :id param-name
              :name param-name
              :value (force anti-forgery/*anti-forgery-token*)}])))
