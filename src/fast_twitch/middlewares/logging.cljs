(ns fast-twitch.middlewares.logging
  "Emits request logs through a configurable logger hook."
  [:require [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [console]])

(defn default-logger
  "Logs request events to console.log."
  [event]
  (.log console (pr-str event)))

(defn request-event
  "Builds the structured log event for a completed request."
  [request response started-at]
  {:method (:request-method request)
   :uri (:uri request)
   :query-string (:query-string request)
   :status (:status response)
   :duration-ms (- (.now js/Date) started-at)
   :request-id (:request-id request)
   :remote-addr (:remote-addr request)
   :real-ip (:real-ip request)
   :user-agent (common/header-value (:headers request) :user-agent)})

(defn log-response
  "Invokes the configured logger for a request/response pair."
  [response request started-at options]
  (let [logger (or (:logger options) default-logger)
        event-fn (or (:event-fn options) request-event)]
    (logger (event-fn request response started-at))
    response))

(defn wrap-logging
  "Wraps a handler with structured completion logging."
  ([handler]
   (wrap-logging handler {}))
  ([handler options]
   (fn
     ([request]
      (let [started-at (.now js/Date)
            response (handler request)]
        (if (common/promise? response)
          (.then response #(log-response % request started-at options))
          (log-response response request started-at options))))
     ([request respond raise]
      (let [started-at (.now js/Date)]
        (handler request
                 #(respond (log-response % request started-at options))
                 raise))))))
