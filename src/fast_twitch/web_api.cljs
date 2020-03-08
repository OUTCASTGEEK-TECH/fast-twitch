(ns fast-twitch.web-api
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [<!]]
            [cljs.core.match :refer-macros [match]]
            [clojure.pprint :refer [pprint]]
            [cognitect.transit :as transit]
            [taoensso.timbre :as log]
            [reagent.dom.server :as rs]
            [bidi.bidi :as bidi]
            [lambdaisland.uri :as uri]
            [fast-twitch.sugar :as ft]
            [fast-twitch.nav :refer [cached-routes]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- render-to-str [widget]
       (rs/render-to-string widget))

(defn- render
       ([body] (pr-str body))
       ([format body]
        (log/debug "Response Format: " format)
        (condp = format
               :html (str
                       "<!DOCTYPE html>"
                       (render-to-str body))
               :json (let [w (transit/writer :json)]
                          (transit/write w body))
               (pr-str body))))

(defn- build-response
       ([body]
        (identity
          {:status  200
           :headers {}
           :cookies []
           :body    (render body)}))
       ([fmt body]
        (identity
          {:status  200
           :headers {}
           :cookies []
           :body    (render fmt body)}))
       ([fmt body options]
        (log/debug "Options: " options)
        (let [{:keys [cookies headers status] :or {cookies [], headers {}, status 200}} options]
             {:status  status
              :headers (clj->js headers)
              :cookies cookies
              :body    (render fmt body)})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROUTING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; (defn channel?
;   [x] ;; Clojure
;   (satisfies? clojure.core.async.impl.protocols/Channel x))

(defn- channel?
       [x]                                                  ;; Clojurescript
       (satisfies? cljs.core.async.impl.protocols/Channel x))

(defn- respond
       [res data]
       (let [{status :status headers :headers cookies :cookies body :body} data]
            ;;(log/debug "Response Data: " (-> data pprint with-out-str))
            (-> res
                (ft/status status)
                (ft/set-headers headers)
                (ft/set-cookies cookies)
                (ft/send body))))

(defn match-route [rts path method]
      (log/debug "Matching: " path " and " method)
      (let [match-data (or
                         (bidi/match-route*
                           rts
                           path
                           {:request-method method})
                         {:route-params   {}
                          :query-params   {}
                          :request-method method
                          :handler        (fn [req]
                                              (send "Not Found"))})]
           (log/debug "Matched Route Data: " (-> match-data pprint with-out-str))
           match-data))

(defn- route-dispatcher [rts handler-fn req res]
       (let [headers (ft/get-headers req)
             path (ft/path req)
             full-url (ft/full-url req)
             method (ft/method req)
             body (ft/body req)
             cookies (ft/cookies req)
             signed-cookies (ft/signed-cookies req)
             csrf-token (ft/csrf-token req)
             match-data (match-route rts path method)
             {route-params   :route-params
              query-params   :query-params
              request-method :request-method
              key-handler    :handler} match-data]
            (let [req-data {:headers        headers
                            :route-params   route-params
                            :query-params   query-params
                            :body           body
                            :csrf-token     csrf-token
                            :cookies        cookies
                            :signed-cookies signed-cookies
                            :path           path
                            :full-url       full-url
                            :request-method request-method}]
                 (log/debug "Request Data: " (-> req-data pprint with-out-str))
                 (let [full-req (merge req-data {:raw-req req})
                       data (handler-fn
                              {:endpoint key-handler
                               :req      full-req})]
                      (if (channel? data)
                        (go
                          (let [response-data (<! data)]
                               ;;(log/debug "Response Data: " (-> response-data pprint with-out-str))
                               (respond res response-data)))
                        (do
                          ;;(log/debug "Response Data: " (-> data pprint with-out-str))
                          (respond res data))))
                 )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [rts handler-fn]
      (reset! cached-routes rts)
      (ft/routes [:all "*" (fn [req res]
                               ;;(log/debug "Route Definitions: " (-> rts pprint with-out-str))
                               (route-dispatcher rts handler-fn req res))]))

(defn send
      [& data]
      (apply build-response data))

(defn redirect [route-key & {:keys [cookies status] :or {status 301}}]
      (do
        (when cookies
              (log/debug "Redirect Cookies: " cookies))
        (send :proceed (name route-key)
              {:headers {:location (bidi/path-for @cached-routes route-key)}
               :cookies cookies
               :status  status})))
