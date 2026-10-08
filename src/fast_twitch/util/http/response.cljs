(ns fast-twitch.util.http.response
  (:require [malli.core :as m]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.util.http.headers :as headers]
            [fast-twitch.util.http.body :as body])
  (:refer-global :only [Response WeakMap]))

(defonce ^:private origins
  (WeakMap.))

(defn ^:dynamic response->map
  "Projects native responses as a preserved resource map by default.
  :ring-map preserves the original status, Headers entries and body projection."
  ([response]
   (let [m {:status (.-status response)
            :headers (headers/lossless-map (.-headers response))
            :body (.-body response)}]
     (.set origins response m)
     (assoc m :fast-twitch.routing/response response)))
  ([response projection]
   (case projection
     :preserved-native (response->map response)
     :ring-map {:status (.-status response)
                :headers (headers/headers->map (.-headers response))
                :body (.-body response)})))

(m/=> response->map
      [:function
       [:=>
        [:cat
         [:fn {:error/code :fast-twitch.http/invalid-result} #(instance? Response %)]]
        :map] [:=> [:cat :any [:enum :ring-map :preserved-native]] :map]])

(set! response->map
      (contracts/instrument 'fast-twitch.util.http.response/response->map response->map))

(defn map->response
  [m]
  (let [origin (:fast-twitch.routing/response m)
        baseline (when origin (.get origins origin))
        wire (select-keys m [:status :headers :body])
        wire (if baseline
               (update wire :headers #(headers/edit-deltas (:headers baseline) %))
               wire)]
    (when (and origin (nil? baseline))
      (throw (ex-info "Unknown response carrier; use response->map to normalize"
                      {:code :fast-twitch.http/invalid-carrier})))
    (when origin
      (body/available! (when (identical? (:body m) (.-body origin)) origin) (:body m)))
    (if (and origin (= wire baseline))
      origin
      (do (when (and origin
                     (or (< (.-status origin) 200)
                         (= "opaque" (.-type origin))
                         (= "opaqueredirect" (.-type origin))))
            (throw (ex-info "Special native response cannot be edited"
                            {:code :fast-twitch.http/unsupported-response-edit})))
          (Response. (body/ring-body (:body m))
                     #js {:status (:status m)
                          :headers (headers/entries (:headers wire))})))))

(defn normalize
  [value]
  (if (instance? Response value) (response->map value) value))
