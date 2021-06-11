(ns fast-twitch.preload
  (:require [cljs.core.match :refer-macros [match]]))

;; Reference: https://developer.mozilla.org/en-US/docs/Web/HTML/Preloading_content

(defn quick-audio [path]
  [:link {:rel "preload" :href path :as "audio"}])

(defn quick-css [path]
  [:link {:rel "preload" :href path :as "style"}])

(defn quick-embed [path]
  [:link {:rel "preload" :href path :as "embed"}])

(defn quick-fetch [path]
  [:link {:rel "preload" :href path :as "fetch"}])

(defn quick-img [path]
  [:link {:rel "preload" :href path :as "image"}])

(defn quick-js [path]
  [:link {:rel "preload" :href path :as "script"}])

(defn quick-obj [path]
  [:link {:rel "preload" :href path :as "object"}])

(defn quick-vid [path]
  [:link {:rel "preload" :href path :as "video"}])

(defn quick-work [path]
  [:link {:rel "preload" :href path :as "worker"}])

(defn preload-styles [styles]
      (for [style-data styles]
           ^{:key style-data}
           (match style-data
                  [:href href] [quick-css href]
                  :else identity)))

(defn preload-scripts [scripts]
      (for [script-data scripts]
           ^{:key script-data}
           (match script-data
                  [:src src] [quick-js src]
                  :else identity)))

(defn load-styles [styles]
      (for [style-data styles]
           ^{:key style-data}
           (match style-data
                  [:href href] [:link {:rel "stylesheet" :href href}]
                  :else identity)))

(defn load-scripts [scripts]
      (for [script-data scripts]
           ^{:key script-data}
           (match script-data
                  [:src src] [:script {:type "text/javascript" :src src}]
                  [:src src :type type] [:script {:type type :src src}]
                  [:src-txt src-txt] [:script {:type "text/javascript"} src-txt]
                  [:src-txt src-txt :type type] [:script {:type type} src-txt]
                  :else identity)))
