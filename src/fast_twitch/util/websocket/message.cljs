(ns fast-twitch.util.websocket.message
  (:require [fast-twitch.util.binary :as binary]
            [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]))

(defn message->map
  [data]
  {:type (if (string? data) :text :binary) :data data})

(def Message
  (m/schema [:multi {:dispatch :type} [:text [:map [:type [:= :text]] [:data :string]]]
             [:binary [:map [:type [:= :binary]] [:data [:fn binary/binary?]]]]]))

(mx/defn ^:dynamic map->message
  "Unwraps a typed message map or preserves a raw native text/binary payload."
  [value :- [:or Message :string [:fn binary/binary?]]]
  (if (map? value) (req! value :data) value))

(set! map->message
      (contracts/instrument 'fast-twitch.util.websocket.message/map->message
                            map->message))
