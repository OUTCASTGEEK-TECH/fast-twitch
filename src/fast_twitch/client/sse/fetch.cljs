(ns fast-twitch.client.sse.fetch
  (:require [cljs.core :refer [await]]
            [malli.core :as m]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.client.http :as http]
            [fast-twitch.util.streams.readers :as readers]
            [fast-twitch.codecs.sse :as framing]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.http.body :as body])
  (:refer-global :only [TextDecoder]))

(def ConnectOptions
  (m/schema [:map {:closed true} [:on-event contracts/Function]
             [:signal {:optional true} contracts/Signal]
             [:transport {:optional true} [:= :sse]]
             [:codec {:optional true} [:= :native]]
             [:streaming {:optional true} contracts/SSELimits]]))

(mx/defn ^{:dynamic true :async true} connect!
  :-
  contracts/PromiseResult
  [request options :- ConnectOptions]
  (let [{:keys! [on-event] :keys [signal streaming] :or {streaming {}}} options
        parser (framing/parser streaming)
        request (cond-> request
                  signal (assoc-in [:fast-twitch.client/options :request-init :signal]
                           signal))
        response (await (http/fetch! request))]
    (when-not (and (= 200 (:status response))
                   (re-find #"(?i)^text/event-stream(?:;|$)"
                            (or (get-in response [:headers "content-type"]) "")))
      (try (await (body/dispose! (:body response))) (catch :default _ nil))
      (v/fail! :fast-twitch.sse/invalid-response :consume-sse! [] :event-stream))
    (let [decoder (TextDecoder. "utf-8" #js {:fatal false})
          emit (^:async fn
                [text]
                (doseq [event (framing/events! parser text)] (await (on-event event))))]
      (await (readers/reduce! (:body response)
                              (^:async fn
                               [_ chunk]
                               (await (emit (.decode decoder chunk #js {:stream true}))))
                              nil
                              {:signal signal :owned? true}))
      (await (emit (.decode decoder))))))

(set! connect! (contracts/instrument 'fast-twitch.client.sse.fetch/connect! connect!))
