(ns fast-twitch.transports.tcp.runtime
  (:require [malli.experimental :as mx]
            [malli.core :as m]
            [malli.util :as mu]
            [fast-twitch.transports.tcp.node :as node]
            [fast-twitch.transports.tcp.bun :as bun]
            [fast-twitch.transports.tcp.deno :as deno]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.contracts :as contracts])
  (:refer-global :only [globalThis]))

(def profiles
  {:node {:connect node/connect! :listen node/listen!}
   :bun {:connect bun/connect! :listen bun/listen!}
   :deno {:connect deno/connect! :listen deno/listen!}})

(defn- operation
  [runtime operation]
  (let [available (cond (aget globalThis "Bun") :bun
                        (aget globalThis "Deno") :deno
                        (fn? (some-> (aget globalThis "process")
                                     (aget "getBuiltinModule")))
                          :node)
        selected (if (= :auto runtime) available runtime)
        host (case selected
               :bun (aget globalThis "Bun")
               :deno (aget globalThis "Deno")
               :node (aget globalThis "process")
               nil)
        native-name (case selected
                      :node "getBuiltinModule"
                      (name operation))]
    (when-not (and host (fn? (aget host native-name)))
      (v/fail! :fast-twitch.contract/unsupported-capability
               operation
               [:runtime]
               :native-tcp))
    (req! (req! profiles selected) operation)))

(def ConnectOptions
  (mu/merge contracts/TCPConnectOptions
            [:map [:transport {:optional true} [:= :tcp]]
             [:codec {:optional true} contracts/TCPCodec]
             [:streaming {:optional true} contracts/TCPStreaming]]))

(mx/defn ^:dynamic connect!
  [description :- ConnectOptions]
  ((operation (get description :runtime :auto) :connect)
    description))

(def ListenOptions
  (m/schema [:map {:closed true} [:hostname {:optional true} :string]
             [:port {:optional true} contracts/ListenPort]
             [:runtime {:optional true} contracts/TCPRuntime]
             [:transport {:optional true} [:= :tcp]]
             [:codec {:optional true} contracts/TCPCodec]
             [:streaming {:optional true} contracts/TCPStreaming]]))

(mx/defn ^:dynamic listen!
  [options :- ListenOptions on-connection :- contracts/Function]
  ((operation (get options :runtime :auto) :listen)
    options
    on-connection))

(set! connect!
      (contracts/instrument 'fast-twitch.transports.tcp.runtime/connect! connect!))

(set! listen! (contracts/instrument 'fast-twitch.transports.tcp.runtime/listen! listen!))
