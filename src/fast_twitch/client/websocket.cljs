(ns fast-twitch.client.websocket
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.validation :as v]
            [fast-twitch.transports.websocket.listener :as listener])
  (:refer-global :only [globalThis Promise URL]))

(def Options
  (m/schema
    [:map {:closed true} [:transport {:optional true} [:= :websocket]]
     [:codec {:optional true} [:= :native]]
     [:url
      [:fn
       #(and (string? %)
             (try (let [parsed (URL. %)]
                    (and (#{"ws:" "wss:"} (.-protocol parsed)) (= "" (.-hash parsed))))
                  (catch :default _ false)))]]
     [:protocols {:optional true}
      [:maybe
       [:and [:vector [:re #"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$"]]
        [:fn #(or (not (vector? %)) (= (count %) (count (set %))))]]]]
     [:listener {:optional true} [:maybe listener/Listener]]
     [:signal {:optional true} contracts/Signal]
     [:on-event {:optional true} [:maybe contracts/Function]]]))

(mx/defn ^:dynamic connect!
  [options :- Options]
  (let [{:keys! [url] :keys [protocols listener signal on-event]} options
        constructor (aget globalThis "WebSocket")
        protocols (or protocols [])
        listener (or listener {})
        aborted? (and signal (.-aborted signal))]
    (when-not (fn? constructor) (v/capability! {} :websocket))
    ((^:async fn
      []
      (when aborted? (throw (.-reason signal)))
      (let [socket (new constructor url (clj->js protocols))
            attached (listener/attach! socket listener {:on-event on-event})
            ready (Promise.withResolvers)
            handlers {"open" (fn [_]
                               (.resolve ready nil))
                      "error" (.-reject ready)
                      "close" (.-reject ready)}
            abort (fn []
                    (.reject ready (.-reason signal)))]
        (try
          (doseq [[type f] handlers] (.addEventListener socket type f))
          (when signal
            (.addEventListener signal "abort" abort #js {:once true})
            (when (.-aborted signal) (abort)))
          (await (.-promise ready))
          (:connection attached)
          (catch :default error
            ((req! attached :dispose!))
            (try (.close socket) (catch :default _ nil))
            (throw error))
          (finally
            (doseq [[type f] handlers] (.removeEventListener socket type f))
            (when signal (.removeEventListener signal "abort" abort)))))))))

(set! connect! (contracts/instrument 'fast-twitch.client.websocket/connect! connect!))
