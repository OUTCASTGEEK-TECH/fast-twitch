(ns fast-twitch.middlewares.timeout
  "Bounds asynchronous handler execution time with a timeout response."
  [:require [cljs.core :refer [await]]
            [fast-twitch.middlewares.common :as common]
            [fast-twitch.util.async.settlement :as settlement]
            [fast-twitch.util.http.response :as response]
            [fast-twitch.util.http.body :as body]]
  [:refer-global :only
                 [Promise clearTimeout setTimeout]])

(def default-timeout-ms
  "The default timeout in milliseconds."
  30000)

(def default-timeout-response
  "The default response returned when a handler exceeds the configured timeout."
  {:status 503
   :headers {"Content-Type" "text/plain"}
   :body "Timed out while reading response\n"})

(defn timeout-response
  "Builds the response returned when a request times out."
  [request options]
  (if-let [handler (:error-handler options)]
    (handler request)
    (or (:error-response options) default-timeout-response)))

(defn ^:async timeout-promise
  "Returns a promise that resolves to a timeout response after timeout-ms."
  [request timeout-ms options]
  (let [expired (Promise.withResolvers)
        timer (setTimeout (.-resolve expired) timeout-ms)]
    (try
      (await (.-promise expired))
      (timeout-response request options)
      (finally (clearTimeout timer)))))

(defn wrap-timeout
  "Wraps a handler so promise or callback responses are bounded by a timeout."
  ([handler]
   (wrap-timeout handler {}))
  ([handler options]
   (let [timeout-ms (or (:timeout-ms options) (:ms options) default-timeout-ms)]
     (fn
       ([request]
        (let [response (handler request)]
          (if (common/promise? response)
            ((^:async fn
              []
              (let [expired (Promise.withResolvers)
                    completed? (atom false)
                    winner (atom nil)
                    select (fn [value]
                             (if (compare-and-set! completed? false true)
                               (do (reset! winner value) value)
                               (do (when-not (identical?
                                               (:body (response/normalize value))
                                               (:body (response/normalize @winner)))
                                     (body/dispose! (:body (response/normalize value))))
                                   @winner)))
                    timer (setTimeout (.-resolve expired) timeout-ms)
                    completed (^:async fn
                               []
                               (try (select (await response))
                                    (catch :default error
                                      (compare-and-set! completed? false true)
                                      (throw error))))
                    timed-out (^:async fn
                               []
                               (await (.-promise expired))
                               (try (select (await (timeout-response request options)))
                                    (catch :default error
                                      (compare-and-set! completed? false true)
                                      (throw error))))]
                (try (await (Promise.race #js [(completed) (timed-out)]))
                     (finally (clearTimeout timer))))))
            response)))
       ([request respond raise]
        (let [completed? (atom false)
              winner (atom nil)
              deliver (fn [response]
                        (reset! winner response)
                        ((^:async fn
                          []
                          (try (let [value (await response)]
                                 (reset! winner value)
                                 (await (respond value)))
                               (catch :default error (raise error))))))
              timer (setTimeout (fn []
                                  (when (compare-and-set! completed? false true)
                                    (try (deliver (timeout-response request options))
                                         (catch :default error (raise error)))))
                                timeout-ms)
              respond-once (fn [response]
                             (if (compare-and-set! completed? false true)
                               (do (clearTimeout timer) (deliver response))
                               ((^:async fn
                                 []
                                 (try (let [value (response/normalize (await response))
                                            selected (try (response/normalize (await
                                                                                @winner))
                                                          (catch :default _ nil))]
                                        (when-not (identical? (:body value)
                                                              (:body selected))
                                          (body/dispose! (:body value))))
                                      (catch :default _ nil))))))
              raise-once (fn [error]
                           (when (compare-and-set! completed? false true)
                             (clearTimeout timer)
                             (raise error)))]
          (try (settlement/discard! (handler request respond-once raise-once))
               (catch :default error (raise-once error)))))))))
