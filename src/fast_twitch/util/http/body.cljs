(ns fast-twitch.util.http.body
  (:require [cljs.core :refer [await]])
  (:refer-global :only [WeakSet]))

(defonce ^:private disposed
  (WeakSet.))

(defn available!
  [origin body]
  (when (or (and origin (aget origin "bodyUsed")) (and body (aget body "locked")))
    (throw (ex-info "Body is consumed or locked"
                    {:code :fast-twitch.http/body-unavailable})))
  body)

(defn dispose!
  [body]
  (when (and body (fn? (aget body "cancel")) (not (.has disposed body)))
    (.add disposed body)
    ((^:async fn
      []
      (try (await (.cancel body)) (catch :default _ nil))))))

(defn replace-body
  [response body]
  (when-not (identical? (:body response) body) (dispose! (:body response)))
  (assoc response :body body))

(defn ring-body
  [body]
  (if (sequential? body) (apply str body) body))
