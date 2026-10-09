(ns fast-twitch.client.http-options
  (:require [malli.core :as m]
            [fast-twitch.util.contracts :as contracts]))

(defn- native-enum
  "Accepts keyword selectors while retaining native wire values at interop boundaries."
  [& values]
  (into [:enum] (concat values (map keyword (remove empty? values)))))

(def RequestInit
  (m/schema
    [:map {:closed true} [:signal {:optional true} contracts/Signal]
     [:credentials {:optional true} (native-enum "omit" "same-origin" "include")]
     [:cache {:optional true}
      (native-enum "default" "no-store"
                   "reload" "no-cache"
                   "force-cache" "only-if-cached")]
     [:mode {:optional true} (native-enum "same-origin" "no-cors" "cors")]
     [:redirect {:optional true} (native-enum "follow" "error" "manual")]
     [:referrer {:optional true} :string]
     [:referrerPolicy {:optional true}
      (native-enum ""
                   "no-referrer" "no-referrer-when-downgrade"
                   "origin"
                     "origin-when-cross-origin"
                   "same-origin" "strict-origin"
                   "strict-origin-when-cross-origin" "unsafe-url")]
     [:integrity {:optional true} :string] [:keepalive {:optional true} :boolean]
     [:duplex {:optional true} (native-enum "half")]]))

(def Options
  (m/schema [:map {:closed true} [:request-init {:optional true} RequestInit]
             [:call {:optional true} [:map {:closed true}]]]))
