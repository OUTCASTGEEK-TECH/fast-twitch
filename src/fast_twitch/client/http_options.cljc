(ns fast-twitch.client.http-options
  (:require [malli.core :as m]
            [fast-twitch.util.contracts :as contracts]))

(def RequestInit
  (m/schema
    [:map {:closed true} [:signal {:optional true} contracts/Signal]
     [:credentials {:optional true} [:enum "omit" "same-origin" "include"]]
     [:cache {:optional true}
      [:enum "default" "no-store" "reload" "no-cache" "force-cache" "only-if-cached"]]
     [:mode {:optional true} [:enum "same-origin" "no-cors" "cors"]]
     [:redirect {:optional true} [:enum "follow" "error" "manual"]]
     [:referrer {:optional true} :string]
     [:referrerPolicy {:optional true}
      [:enum "" "no-referrer" "no-referrer-when-downgrade" "origin"
       "origin-when-cross-origin" "same-origin" "strict-origin"
       "strict-origin-when-cross-origin" "unsafe-url"]]
     [:integrity {:optional true} :string] [:keepalive {:optional true} :boolean]
     [:duplex {:optional true} [:enum "half"]]]))

(def Options
  (m/schema [:map {:closed true} [:request-init {:optional true} RequestInit]
             [:call {:optional true} [:map {:closed true}]]]))
