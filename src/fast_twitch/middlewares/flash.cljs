(ns fast-twitch.middlewares.flash
  "Moves flash data between the session and request or response maps."
  [:require [fast-twitch.middlewares.common :as common]])

(defn flash-request
  "Loads flash data from the session and clears it for the next request."
  [request]
  (let [flash (get-in request [:session :_flash])]
    (cond-> (update request :session dissoc :_flash)
      (some? flash)
      (assoc :flash flash))))

(defn flash-response
  "Stores outgoing flash data back into the session."
  [response request]
  (let [session (or (:session response) (:session request) {})]
    (cond
      (contains? response :flash)
      (-> response
          (dissoc :flash)
          (assoc :session (assoc session :_flash (:flash response))))

      (:flash request)
      (assoc response :session session)

      :else
      response)))

(defn wrap-flash
  "Wraps a handler with flash loading and persistence."
  [handler]
  (common/wrap-request-response handler flash-request flash-response))
