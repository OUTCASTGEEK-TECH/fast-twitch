(ns ddc.util.anti-forgery
  [:require [ddc.middlewares.anti-forgery :as anti-forgery]])

(defn anti-forgery-field []
  [:input {:type "hidden"
           :id "__anti-forgery-token"
           :name "__anti-forgery-token"
           :value (force anti-forgery/*anti-forgery-token*)}])
