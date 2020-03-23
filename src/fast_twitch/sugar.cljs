(ns fast-twitch.sugar
  "Lightweight interface to requirejs."
  ;;(:refer-clojure :exclude [set get])
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :refer [lower-case]]
            [clojure.walk :refer [keywordize-keys]]
            [taoensso.timbre :as log]
            ["express" :as express]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn app
      "Create a express app instance."
      []
      (express))

(defn router
      "Create a express router instance."
      [& [opts]]
      (.Router express (clj->js opts)))

(defn routes
      "Create a router instance and attach
      handlers to it."
      [& args]
      (let [app (router)]
           (reduce (fn [app [verb path handler]]
                       (condp = verb
                              :all (. app (all path handler))
                              :get (. app (get path handler))
                              :post (. app (post path handler))
                              :put (. app (put path handler))
                              :delete (. app (delete path handler))
                              app))
                   app
                   args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Request & Response Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send
      [res data]
      (.send res data))

(defn status
      [res code]
      (.status res code))

(defn set-headers
      [res headers]
      (.set res (clj->js headers)))

(defn get-headers
      [req]
      (let [headers (-> (.-headers req) (js->clj :keywordize-keys true))]
           ;;(log/debug "Request Headers: " headers)
           headers))

(defn path
      [req]
      (let [path (.-url req)]
           path))

(defn full-url
      [req]
      (let [protocol (.-protocol req)
            host (.get req "host")
            ;;port (.. req -app -settings -port)
            raw-path (.-url req)
            path (path req)]
           ;;(log/debug "URL Parts: " protocol " + " host " + " port " + " url)
           (str
             protocol "://" host path)))

(defn method
      [req]
      (let [m (.-method req)]
           (-> m
               lower-case
               keyword)))

(defn body
      [req]
      (-> req
          .-body
          (js->clj :keywordize-keys true)))

(defn csrf-token [req]
  (when (.hasOwnProperty req "csrfToken")
    (.csrfToken req)))

(defn set-cookies
      [res cookies]
      (when (not-empty cookies)
            (log/debug "Setting Cookies: " cookies))
      (doseq [c cookies
              :let [{:keys [name value opts]} c]]
             (.cookie res name value (clj->js opts)))
      ;; Make sure to return the response object!!!!
      res)

(defn cookies [req]
      (-> req
          .-cookies
          (js->clj :keywordize-keys true)))

(defn signed-cookies [req]
      (-> req
          .-signedCookies
          (js->clj :keywordize-keys true)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-middleware
      "Attach middlware to express app."
      ([app handler]
       (.use app handler))
      ([app path handler]
       (.use app path handler)))

(defn listen
      "Shortcut for start http server."
      ([app port]
       (.listen app port "0.0.0.0"))
      ([app port callback]
       (.listen app port "0.0.0.0" callback)))

;; (defn set
;;   [app key value]
;;   (.set app key value)
;;   app)

;; (defn get
;;   [app key]
;;   (.get app key))

(defn enable
      [app key]
      (.enable app key)
      app)

(defn disable
      [app key]
      (.disable app key)
      app)

(defn enabled?
      [app key]
      (.enabled app key))

(defn disabled?
      [app key]
      (.disabled app key))

;(defn static
;  ([app path]
;   (use app ((aget express "static") path)))
;  ([app path mount]
;   (use app mount ((aget express "static") path))))

