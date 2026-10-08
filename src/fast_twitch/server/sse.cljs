(ns fast-twitch.server.sse
  "Pull-based SSE responses for ordinary Ring handlers."
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.codecs.sse :as framing])
  (:refer-global :only [ReadableStream TextEncoder]))

(def ^:private Options
  (m/schema [:map {:closed true} [:on-cancel {:optional true} [:maybe contracts/Function]]
             [:headers {:optional true} [:maybe :map]]]))

(mx/defn ^:dynamic response
  ([next-event :- contracts/Function]
   (response next-event {}))
  ([next-event :- contracts/Function options :- Options]
   (let [{:keys [on-cancel headers]} options
         ended? (atom false)
         dispose (fn [reason]
                   (when (compare-and-set! ended? false true)
                     (when on-cancel (on-cancel reason))))
         stream (ReadableStream.
                  #js {:pull (^:async fn
                              [controller]
                              (try (let [event (await (next-event))]
                                     (when-not @ended?
                                       (if (nil? event)
                                         (do (reset! ended? true) (.close controller))
                                         (.enqueue controller
                                                   (.encode (TextEncoder.)
                                                            (framing/encode event))))))
                                   (catch :default error
                                     (try (await (dispose error)) (catch :default _ nil))
                                     (.error controller error))))
                       :cancel dispose}
                  #js {:highWaterMark 0})]
     {:status 200
      :headers (merge {"content-type" "text/event-stream; charset=utf-8"
                       "cache-control" "no-cache"}
                      headers)
      :body stream})))

(set! response (contracts/instrument 'fast-twitch.server.sse/response response))
