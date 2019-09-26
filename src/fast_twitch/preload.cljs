(ns fast-twitch.preload)

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
