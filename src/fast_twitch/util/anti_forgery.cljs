(ns fast-twitch.util.anti-forgery
  [:require [fast-twitch.middlewares.anti-forgery :as anti-forgery]])

(defn anti-forgery-field []
  [:input {:type "hidden"
           :id "__anti-forgery-token"
           :name "__anti-forgery-token"
           :value (force anti-forgery/*anti-forgery-token*)}])
