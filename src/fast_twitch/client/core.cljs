(ns fast-twitch.client.core
  "Native clients configured with ordinary request and option maps."
  (:require [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.client.http :as http]
            [fast-twitch.transports.tcp.runtime :as tcp]
            [fast-twitch.util.tcp :as tcp-data]
            [fast-twitch.transports.websocket.protocols :as socket]
            [fast-twitch.client.websocket :as websocket]
            [fast-twitch.client.sse.fetch :as sse]
            [fast-twitch.client.sse.event-source :as event-source]
            [fast-twitch.client.messaging :as messaging]
            [fast-twitch.transports.websocket.connection :as connection]
            [fast-twitch.util.streams.readers :as readers]
            [fast-twitch.util.validation :as v]))

(defn message-channel!
  "Creates a native MessageChannel. :codec (:native or :json) configures both
  ports; optional :on-event receives port2 messages. Returns :port1/:port2 handles."
  ([]
   (message-channel! {}))
  ([options]
   (messaging/create-channel! (dissoc options :on-event) options)))

(defn read-body!
  "Reads a request/response body with :codec and bounded reader options."
  ([message]
   (readers/read-body! message))
  ([message options]
   (readers/read-body! message options)))

(defn read-stream!
  "Reads a native readable stream with :codec and bounded reader options."
  ([stream]
   (readers/read! stream))
  ([stream options]
   (readers/read! stream options)))

(defn reduce-stream!
  "Reduces bytes, or incremental UTF-8 text with :codec :text, awaiting the reducer.
  Borrowed readers release locks; owned readers cancel on early termination or failure."
  ([stream reducer initial]
   (readers/reduce! stream reducer initial))
  ([stream reducer initial options]
   (readers/reduce! stream reducer initial options)))

(defn request!
  "Fetches a request map according to configuration. :transport is :http
  (default) or :sse. HTTP supports :codec :native/:bytes/:text/:json, :request-init,
  :streaming and middleware sequences. SSE uses :on-event, :signal and streaming
  :max-line-bytes/:max-event-bytes, with its native event-map codec."
  ([request]
   (request! request {}))
  ([request options]
   (case (:transport options :http)
     :http (http/fetch! request options)
     :sse (sse/connect! request options)
     (v/fail! :fast-twitch.contract/unsupported-transport
              :request!
              [:transport]
              #{:http :sse}))))

(defn connect!
  "Connects an endpoint map according to :transport (:tcp, :websocket or :event-source).
  TCP supports :codec :native/:bytes/:text and :streaming bounded reader settings,
  with optional :runtime/:tls?/:signal. receive! reads through EOF, without framing.
  WebSocket uses its native message codec and :protocols/:listener/:on-event/:signal;
  streaming and other codecs are rejected. It resolves to the socket on open;
  use send! and close! with that handle. EventSource uses :url/:on-event and optional
  :event-types/:with-credentials?. Native adapters are selected internally."
  [endpoint options]
  (case (:transport options)
    :tcp (tcp/connect! (merge endpoint options))
    :websocket (websocket/connect! (merge endpoint options))
    :event-source (event-source/connect! (merge endpoint options))
    (v/fail! :fast-twitch.contract/unsupported-transport
             :connect!
             [:transport]
             #{:tcp :websocket :event-source})))

(defn ^:dynamic send!
  "Sends data through a configured TCP connection, native WebSocket, or message
  port. TCP :text encodes UTF-8; byte codecs preserve native bytes. Optional
  :transfer applies only to message ports. No framing or peer receipt is implied."

  ([handle data]
   (send! handle data {}))
  ([handle data options]
   (cond (satisfies? socket/Socket handle) (socket/-send handle data)
         (:fast-twitch.tcp/socket handle) (tcp-data/send! handle data)
         :else ((v/capability! handle :post!) data (:transfer options [])))))

(m/=> send!
      [:function [:=> [:cat :any :any] :any]
       [:=>
        [:cat
         [:altn [:websocket [:cat contracts/WebSocketHandle :any contracts/EmptyOptions]]
          [:tcp [:cat contracts/TCPPeer :any contracts/EmptyOptions]]
          [:port
           [:cat [:and [:not contracts/WebSocketHandle] [:not contracts/TCPPeer]] :any
            contracts/TransferOptions]]]] :any]])

(set! send! (contracts/instrument 'fast-twitch.client.core/send! send!))

(defn receive!
  "Collects a TCP connection through EOF using its configured codec/streaming
  limits. WebSocket and message-port messages use their event listeners."
  [connection]
  (tcp-data/receive! connection))

(defn half-close!
  "Ends TCP writes while retaining reads."
  [connection]
  ((v/capability! connection :half-close!)))

(mx/defn ^{:dynamic true :private true} close-handle!
  [handle _options :- contracts/EmptyOptions]
  ((v/capability! handle :close!)))

(defn close!
  "Closes a socket or owned connection/port/source. WebSocket options: :code/
  :reason; other connection handles accept no close options."
  ([handle]
   (close! handle {}))
  ([handle options]
   (if (satisfies? socket/Socket handle)
     (connection/close! handle options)
     (close-handle! handle options))))

(set! close-handle!
      (contracts/instrument 'fast-twitch.client.core/close-handle! close-handle!))
