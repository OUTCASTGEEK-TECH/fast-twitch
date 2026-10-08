(ns fast-twitch.server.core
  "Ring HTTP response helpers and native servers configured with option maps."
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [fast-twitch.client.core :as client]
            [malli.util :as mu]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.macros :refer-macros [serve shutdown]]
            [fast-twitch.routing :as routing]
            [fast-twitch.transports.tcp.runtime :as tcp]
            [fast-twitch.middlewares.common :as common]
            [fast-twitch.transports.websocket.protocols :as socket]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.streams.readers :as readers]
            [fast-twitch.codecs.json :as json]))

(def ^:private HTTPListenOptions
  (mu/merge
    contracts/HTTPBodyOptions
    [:map [:transport {:optional true} [:= :http]] [:hostname {:optional true} :string]
     [:port {:optional true} contracts/ListenPort] [:reuse-port {:optional true} :boolean]
     [:on-listen {:optional true} contracts/Function] [:async? {:optional true} :boolean]
     [:protocol {:optional true} :string] [:remote-addr {:optional true} :string]]))

(def ^:private ResponseOptions
  (m/schema [:map {:closed true} [:status {:optional true} [:int {:min 100 :max 599}]]
             [:headers {:optional true} [:map]]
             [:codec {:optional true} [:enum :native :json]]]))

(def ^:private StopOptions
  (m/schema [:map {:closed true} [:force {:optional true} :boolean]]))

(mx/defn ^:dynamic response
  "Returns a Ring HTTP response map for body, with optional :status/:headers
  and :codec (:native by default, or :json for ordinary JSON data)."
  ([body]
   (response body {}))
  ([body options :- ResponseOptions]
   (let [{:keys [status headers codec] :or {status 200 headers {} codec :native}} options]
     (cond-> {:status status :headers headers :body body}
       (= codec :json) (json/encode-body)))))

(defn- configured-http-handler
  [handler {:keys [codec streaming] :or {codec :native streaming {}}}]
  (if (= codec :native)
    handler
    (common/wrap-request-response
      handler
      (^:async fn
       [request]
       (if (:body request)
         (assoc request
           :body (await (readers/read! (:body request) (assoc streaming :codec codec))))
         request))
      (fn [response _]
        (if (and (= codec :json)
                 (not (contains? response :ring.websocket/listener))
                 (not (#{204 205 304} (:status response))))
          (json/encode-body response)
          response)))))

(mx/defn ^{:dynamic true :private true} listen-http!
  [handler :- contracts/Function options :- HTTPListenOptions]
  (serve :handler (routing/ft-handler
                    (configured-http-handler handler options)
                    (merge {:protocol "HTTP/1.1" :remote-addr "unknown"}
                           (select-keys options [:async? :protocol :remote-addr])))
         :hostname (:hostname options)
         :port (:port options)
         :reuse-port (:reuse-port options)
         :on-listen (:on-listen options)))

(defn listen!
  "Starts a server for a handler and configuration. :transport is :http
  (default) or :tcp. HTTP :codec :native preserves Ring bodies; :bytes/:text/:json
  decode present request bodies using :streaming settings, and JSON encodes HTTP
  response bodies. Standard Ring handler arities and :async? remain available.
  TCP supports :native/:bytes/:text; each connection carries its codec/streaming
  settings for client/send! and client/receive!. Reads continue through EOF without framing.
  Native options belong to the selected transport; invalid combinations reject
  before listening. Returns the existing native server/listener handle."
  [handler options]
  (case (:transport options :http)
    :http (listen-http! handler options)
    :tcp (tcp/listen! options handler)
    (v/fail! :fast-twitch.contract/unsupported-transport
             :listen!
             [:transport]
             #{:http :tcp})))

(mx/defn ^{:dynamic true :private true} close-server!
  [handle options :- StopOptions]
  (shutdown handle :force (:force options)))

(defn close!
  "Closes a native HTTP server, TCP listener/peer or Ring WebSocket. HTTP
  options: :force; WebSocket options: :code/:reason; TCP accepts no close options."
  ([handle]
   (close! handle {}))
  ([handle options]
   (if (or (map? handle) (satisfies? socket/Socket handle))
     (client/close! handle options)
     (close-server! handle options))))

(set! close-server!
      (contracts/instrument 'fast-twitch.server.core/close-server! close-server!))

(set! listen-http!
      (contracts/instrument 'fast-twitch.server.core/listen-http! listen-http!))

(set! response (contracts/instrument 'fast-twitch.server.core/response response))
