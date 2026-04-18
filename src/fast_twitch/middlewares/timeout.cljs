(ns fast-twitch.middlewares.timeout
  "Bounds asynchronous handler execution time with a timeout response."
  [:require [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Promise clearTimeout setTimeout]])

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

(defn timeout-promise
  "Returns a promise that resolves to a timeout response after timeout-ms."
  [request timeout-ms options]
  (Promise.
   (fn [respond _raise]
     (setTimeout #(respond (timeout-response request options))
                 timeout-ms))))

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
            (Promise.race #js [response (timeout-promise request timeout-ms options)])
            response)))
       ([request respond raise]
        (let [completed? (atom false)
              timer (setTimeout
                     (fn []
                       (when-not @completed?
                         (reset! completed? true)
                         (respond (timeout-response request options))))
                     timeout-ms)
              respond-once (fn [response]
                             (when-not @completed?
                               (reset! completed? true)
                               (clearTimeout timer)
                               (respond response)))
              raise-once (fn [error]
                           (when-not @completed?
                             (reset! completed? true)
                             (clearTimeout timer)
                             (raise error)))]
          (handler request respond-once raise-once)))))))
