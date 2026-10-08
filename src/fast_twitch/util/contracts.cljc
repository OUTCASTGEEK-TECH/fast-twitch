(ns fast-twitch.util.contracts
  "Shared native option schemas. Optional entries preserve the caller's defaults."
  (:require [malli.core :as m]
            [malli.util :as mu]
            #?(:cljs [fast-twitch.transports.websocket.protocols :as socket]))
  #?(:cljs (:refer-global :only [Promise])))

#?(:cljs (def PromiseResult
           (m/schema [:fn #(instance? Promise %)])))

#?(:cljs (def WebSocketHandle
           (m/schema [:fn #(satisfies? socket/Socket %)])))

#?(:cljs (def TCPPeer
           (m/schema [:and [:not WebSocketHandle]
                      [:fn #(boolean (:fast-twitch.tcp/socket %))]])))

(def EmptyOptions
  (m/schema [:map {:closed true}]))

(def Function
  (m/schema [:fn fn?]))

(def TCPRuntime
  (m/schema [:enum :auto :node :bun :deno]))

(def PositiveInteger
  (m/schema [:int {:min 1}]))

(def ConnectPort
  (m/schema [:and integer? [:>= 1] [:<= 65535]]))

(def ListenPort
  (m/schema [:int {:min 0 :max 65535}]))

(def Signal
  (m/schema
    [:maybe
     [:fn
      #?(:cljs (fn [signal]
                 (and (boolean? (aget signal "aborted"))
                      (fn? (aget signal "addEventListener"))
                      (fn? (aget signal "removeEventListener"))))
         :clj any?)]]))

(def TCPConnectOptions
  (m/schema [:map {:closed true} [:hostname :string] [:port ConnectPort]
             [:tls? {:optional true} :boolean] [:signal {:optional true} Signal]
             [:runtime {:optional true} TCPRuntime]]))

(def ReaderMode
  (m/schema [:enum :default :byob]))

(def ChunkBytes
  (m/schema [:int {:min 1 :max 1048576}]))

(def ReaderOptions
  (m/schema [:map {:closed true} [:owned? {:optional true} :boolean]
             [:signal {:optional true} Signal] [:mode {:optional true} ReaderMode]
             [:chunk-bytes {:optional true} ChunkBytes]]))

(def CollectOptions
  (mu/assoc ReaderOptions [:max-bytes {:optional true}] PositiveInteger))

(def HTTPCodec
  (m/schema [:enum :native :bytes :text :json]))

(def HTTPBodyOptions
  (m/schema
    [:and
     [:map {:closed true} [:codec {:optional true} HTTPCodec]
      [:streaming {:optional true} CollectOptions]]
     [:fn #(not (and (= :native (get % :codec :native)) (contains? % :streaming)))]]))

(def TCPCodec
  (m/schema [:enum :native :bytes :text]))

(def ReadCodec
  (m/schema [:enum :bytes :text :json]))

(def TCPStreaming
  (m/schema
    [:and CollectOptions
     [:fn
      #(or (not (map? %))
           (not (or (= :byob (:mode %)) (contains? % :chunk-bytes))))]]))

(def SSELimits
  (m/schema [:map {:closed true} [:max-line-bytes {:optional true} [:int {:min 1}]]
             [:max-event-bytes {:optional true} [:int {:min 1}]]]))

(def SSEField
  (m/schema [:and :string [:fn #(not (re-find #"[\r\n]" %))]]))

(def SSEEventKeys
  (m/schema [:map {:closed true} [:data :string]
             [:event {:optional true} [:or [:maybe SSEField] [:= false]]]
             [:id {:optional true}
              [:maybe [:and SSEField [:fn #(not (re-find #"\u0000" %))]]]]
             [:retry {:optional true} [:maybe [:int {:min 0 :max 9007199254740991}]]]]))

(def TransferOptions
  (m/schema [:map {:closed true} [:transfer {:optional true} :any]]))

(defn report!
  "Malli's reporting hook keeps schema locations without retaining argument values."
  [operation type {:keys [input output guard args value schema arity arities explain]}]
  (let [explain (or explain
                    (when-let [contract (or input output guard)]
                      (m/explain contract
                                 (cond input args
                                       output value
                                       :else [args value]))))
        errors (:errors explain)
        code (some #(-> %
                        :schema
                        m/properties
                        :error/code)
                   errors)]
    (m/-fail! type
              (cond-> {:operation operation
                       :schema (m/form schema)
                       :errors (mapv #(select-keys % [:path :in :type]) errors)}
                code (assoc :code code)
                arity (assoc :arity arity)
                arities (assoc :arities arities)))))

(defn instrument
  "Enforces a registered function contract once when its namespace loads."
  [operation f]
  (m/-instrument
    (assoc (get-in (m/function-schemas :cljs)
                   [(symbol (namespace operation)) (symbol (name operation))])
      :report (partial report! operation))
    f))
