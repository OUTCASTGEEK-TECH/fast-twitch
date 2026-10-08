(ns fast-twitch.server.runtime.websocket
  (:require [fast-twitch.server.websocket :as upgrade]
            [fast-twitch.transports.websocket.listener :as listener]
            [fast-twitch.transports.websocket.connection :as connection]
            [fast-twitch.transports.websocket.bun :as bun])
  (:refer-global :only [globalThis WeakMap undefined]))

(defn setup
  [runtime handler]
  (let [sockets (atom #{})
        bun-connections (WeakMap.)
        cleanup! (fn []
                   (doseq [c @sockets]
                     (connection/close! c {:code 1001 :reason "Server shutting down"})))
        deno-capability
          {:upgrade
             (fn [request m]
               (let [options #js {}
                     _ (when-let [protocol (:ring.websocket/protocol m)]
                         (aset options "protocol" protocol))
                     result (.upgradeWebSocket (aget globalThis "Deno") request options)
                     attached (listener/attach! (.-socket result)
                                                (req! m :ring.websocket/listener)
                                                {:on-dispose #(swap! sockets disj %)})]
                 (swap! sockets conj (req! attached :connection))
                 (.-response result)))}
        delivery (fn [event]
                   (fn [socket data]
                     (listener/invoke! (aget (.-data socket) "listener")
                                       event
                                       (.get bun-connections socket)
                                       [data])))
        bun-websocket
          #js {:open (fn [socket]
                       (let [data (.-data socket)
                             c (connection/connection socket
                                                      {:open? #(= 1 (.-readyState %))
                                                       :send bun/send!
                                                       :ping (fn [s data]
                                                               (.ping s data))
                                                       :pong (fn [s data]
                                                               (.pong s data))
                                                       :close (fn [s code reason]
                                                                (.close s code reason))})]
                         (.set bun-connections socket c)
                         (swap! sockets conj c)
                         (listener/invoke! (aget data "listener") :on-open c [])))
               :message (delivery :on-message)
               :close (fn [socket code reason]
                        (let [c (.get bun-connections socket)]
                          (swap! sockets disj c)
                          (.delete bun-connections socket)
                          (listener/invoke! (aget (.-data socket) "listener")
                                            :on-close
                                            c
                                            [code reason])))
               :ping (delivery :on-ping)
               :pong (delivery :on-pong)}
        wrapped
          (fn
            ([request]
             (let [capability (if (= runtime :deno) deno-capability {})]
               (upgrade/bind! request capability)
               (handler request)))
            ([request info]
             (if (= runtime :bun)
               (do
                 (upgrade/bind!
                   request
                   {:upgrade
                      (fn [request m]
                        (let [opts #js {:data #js {:listener (:ring.websocket/listener
                                                               m)}}]
                          (when-let [protocol (:ring.websocket/protocol m)]
                            (aset opts "headers" #js {"Sec-WebSocket-Protocol" protocol}))
                          (when-not (.upgrade info request opts)
                            (throw (ex-info "Native upgrade rejected"
                                            {:code
                                               :fast-twitch.websocket/upgrade-failed})))
                          ;; Bun consumes undefined as completed upgrade, never an
                          ;; HTTP map.
                          undefined))})
                 (handler request info))
               (let [capability (if (= runtime :deno) deno-capability {})]
                 (upgrade/bind! request capability)
                 (handler request info)))))]
    {:handler wrapped :cleanup! cleanup! :websocket bun-websocket}))
