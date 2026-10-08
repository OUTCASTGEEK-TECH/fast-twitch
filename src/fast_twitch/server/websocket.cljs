(ns fast-twitch.server.websocket
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [clojure.string :as str]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.http.headers :as headers]
            [fast-twitch.transports.websocket.listener :as listener])
  (:refer-global :only [Request WeakMap]))

(defonce ^:private ingress
  (WeakMap.))

(defn bind!
  ([view]
   (when-let [request (:fast-twitch.routing/request view)]
     (when-let [context (.get ingress request)]
       (aset context "view" view))))
  ([request capability]
   (.set ingress request #js {:capability capability})
   request))

(defn listener-response?
  [value]
  (and (map? value) (contains? value :ring.websocket/listener)))

(def ListenerResponse
  (m/schema [:map [:ring.websocket/listener listener/Listener]
             [:ring.websocket/protocol {:optional true} [:maybe :string]]]))

(def UpgradeRequest
  (let [observed (fn [view]
                   (if (instance? Request view)
                     (or (some-> (.get ingress view)
                                 (aget "view"))
                         view)
                     view))
        current (fn [view]
                  (let [view (observed view)]
                    (if (instance? Request view)
                      (headers/headers->map (.-headers view))
                      (headers/normalized (:headers view)))))
        handshake (fn [value]
                    (into {}
                          (filter (fn [[key _]]
                                    (or (#{"upgrade" "connection" "host" "origin"} key)
                                        (str/starts-with? key "sec-websocket-"))))
                          value))]
    (m/schema
      [:and [:tuple [:fn #(instance? Request %)] ListenerResponse]
       [:fn {:error/code :fast-twitch.websocket/invalid-upgrade}
        (fn [[view _]]
          (= "websocket"
             (some-> (get (current view) "upgrade")
                     str/lower-case)))]
       [:fn {:error/code :fast-twitch.websocket/invalid-protocol}
        (fn [[view response]]
          (or (nil? (:ring.websocket/protocol response))
              (contains? (set (map str/trim
                                (str/split
                                  (get (current view) "sec-websocket-protocol" "")
                                  #",")))
                         (:ring.websocket/protocol response))))]
       [:fn {:error/code :fast-twitch.websocket/unsupported-request-edit}
        (fn [[view _]]
          (let [request view
                view (observed request)]
            (or (instance? Request view)
                (and (= (str/upper-case (name (req! view :request-method)))
                        (.-method request))
                     (= (handshake (current request))
                        (handshake (headers/headers->map (.-headers request))))))))]])))

(mx/defn ^:dynamic upgrade!
  [context :- UpgradeRequest]
  (let [[request response] context]
    ((v/capability! (some-> (.get ingress request)
                            (aget "capability"))
                    :upgrade)
      request
      response)))

(set! upgrade!
      (contracts/instrument 'fast-twitch.server.websocket/upgrade! upgrade!))
