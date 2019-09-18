(ns express.web-api
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [<!]]
            [cljs.core.match :refer-macros [match]]
            [clojure.pprint :refer [pprint]]
            [taoensso.timbre :as log]
            [reagent.dom.server :as rs]
            [express.sugar :as ex]
            [bidi.bidi :as bidi]
            [lambdaisland.uri :as uri]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- render-to-str [widget]
  (rs/render-to-string widget))

(defn- clj->json [data]
  (.. js/JSON stringify (clj->js data)))

(defn- render
  ([body] (pr-str body))
  ([format body]
   (log/debug "Format: " format)
   (log/debug "Body: " body)
   (condp = format
     :html (str
            "<!DOCTYPE html>"
            (render-to-str body))
     :json (clj->json body)
     (pr-str body))))

(defn- build-response
  ([body]
   (identity
    {:status 200
     :headers {}
     :body (render body)}))
  ([fmt body]
   (identity
    {:status 200
     :headers {}
     :body (render fmt body)}))
  ([fmt body options]
   (log/debug "Options: " options)
   (let [{:keys [headers status] :or {headers {}, status 200}} options]
     {:status status
      :headers headers
      :body (render fmt body)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROUTING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; (defn channel?
;   [x] ;; Clojure
;   (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn- channel?
  [x] ;; Clojurescript
  (satisfies? cljs.core.async.impl.protocols/Channel x))

(defn- respond
  [res data]
  (let [{status :status headers :headers body :body} data]
        ;;(log/debug "Response Data: " (-> data pprint with-out-str))
    (-> res
        (ex/status status)
        (ex/set-headers (clj->js headers))
        (ex/send body))))

(defn match-route [rts path method]
  (log/debug "Matching: " path " and " method)
  (let [match-data (or
                    (bidi/match-route*
                     rts
                     path
                     {:request-method method})
                    {:route-params {}
                     :query-params {}
                     :request-method method
                     :handler (fn [req]
                                (send "Not Found"))})]
    (log/debug "Matched Route Data: " (-> match-data pprint with-out-str))
    match-data))

(defn- route-dispatcher [rts handler-fn req res]
  (let [headers (ex/get-headers req)
        path (ex/path req)
        full-url (ex/full-url req)
        method (ex/method req)
        body (ex/body req)
        csrf-token (ex/csrf-token req)
        match-data (match-route rts path method)
        {route-params :route-params
         query-params :query-params
         request-method :request-method
         key-handler :handler} match-data
        data (handler-fn
              {:endpoint key-handler
               :req {:headers headers
                     :route-params route-params
                     :query-params query-params
                     :body body
                     :csrf-token csrf-token
                     :path path
                     :full-url full-url
                     :request-method request-method
                     :raw-req req}})]
    (if (channel? data)
      (go
        (let [response-data (<! data)]
                ;;(log/debug "Response Data: " (-> response-data pprint with-out-str))
          (respond res response-data)))
      (do
            ;;(log/debug "Response Data: " (-> data pprint with-out-str))
        (respond res data)))))

(def cached-routes (atom []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [rts handler-fn]
  (reset! cached-routes rts)
  (ex/routes [:all "*" (fn [req res]
                         ;;(log/debug "Route Definitions: " (-> rts pprint with-out-str))
                         (route-dispatcher rts handler-fn req res))]))

(defn path-for [key-handler]
  (let [rts @cached-routes
        path (or (bidi/path-for rts key-handler) "#link_to_nowhere")]
    path))

(defn send
  [& data]
  (apply build-response data))

(defn redirect [route-key]
  (send :proceed (name route-key)
        {:headers {:location (path-for route-key)}
         :status  301}))
