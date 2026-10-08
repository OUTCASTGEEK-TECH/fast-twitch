(ns fast-twitch.util.tcp
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.streams.readers :as readers]
            [fast-twitch.util.binary :as binary])
  (:refer-global :only [TextEncoder]))

(defn connection-map
  [socket readable writable operations
   {:keys [codec streaming] :or {codec :native streaming {}}}]
  (merge {:fast-twitch.tcp/socket socket
          :readable readable
          :writable writable
          :fast-twitch.tcp/codec codec
          :fast-twitch.tcp/streaming streaming}
         operations))

(defn receive!
  [connection]
  (readers/read! (req! connection :readable)
                 (assoc (:fast-twitch.tcp/streaming connection {})
                   :codec (case (:fast-twitch.tcp/codec connection :native)
                            :native :bytes
                            :bytes :bytes
                            :text :text))))

(defn ^{:dynamic true :async true} send!
  [connection data]
  (let [data (if (= :text (:fast-twitch.tcp/codec connection))
               (.encode (TextEncoder.) data)
               (binary/bytes data))
        writer (.getWriter (req! connection :writable))]
    (try (await (.-ready writer))
         (await (.write writer data))
         (finally (.releaseLock writer)))))

(m/=> send!
      [:=>
       [:cat
        [:altn [:text [:cat [:map [:fast-twitch.tcp/codec [:= :text]]] :string]]
         [:bytes
          [:cat [:map [:fast-twitch.tcp/codec {:optional true} [:enum :native :bytes]]]
           [:fn binary/binary?]]]]] :any])

(set! send! (contracts/instrument 'fast-twitch.util.tcp/send! send!))
