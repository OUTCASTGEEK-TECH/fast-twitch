(ns fast-twitch.middlewares.flash
  [:require [fast-twitch.middlewares.common :as common]])

(defn flash-request [request]
  (let [flash (get-in request [:session :_flash])]
    (cond-> (update request :session dissoc :_flash)
      (some? flash)
      (assoc :flash flash))))

(defn flash-response [response request]
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

(defn wrap-flash [handler]
  (common/wrap-request-response handler flash-request flash-response))
