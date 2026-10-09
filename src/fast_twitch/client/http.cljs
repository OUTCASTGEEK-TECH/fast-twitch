(ns fast-twitch.client.http
  (:require [cljs.core :refer [await]]
            [malli.util :as mu]
            [malli.experimental :as mx]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.client.http-options :as http-options]
            [fast-twitch.util.http.request :as request]
            [fast-twitch.util.http.response :as response]
            [fast-twitch.util.streams.readers :as readers]
            [fast-twitch.codecs.json :as json]
            [fast-twitch.util.validation :as v])
  (:refer-global :only [globalThis]))

(def Options
  (mu/merge
    contracts/HTTPBodyOptions
    [:map [:transport {:optional true} [:or [:= :http] contracts/Function]]
     [:request-init {:optional true} http-options/RequestInit]
     [:request-check {:optional true} contracts/Function]
     [:request-middleware {:optional true} [:maybe [:sequential contracts/Function]]]
     [:response-middleware {:optional true} [:maybe [:sequential contracts/Function]]]]))

(defn- ^:async apply-middleware!
  [initial middleware]
  (loop [value initial
         remaining middleware]
    (if-let [transform (first remaining)]
      (recur (await (transform value)) (next remaining))
      value)))

(mx/defn ^{:dynamic true :private true} fetch-native!
  [m options :- http-options/Options transport request-check]
  ;; Target checks see effective options after middleware, before
  ;; allocating/consuming a Request.
  (when request-check (request-check m options))
  (let [request (request/map->request m (:request-init options))]
    (if (fn? transport)
      (transport request (get options :call {}))
      (do (when-not (fn? (aget globalThis "fetch")) (v/capability! {} :fetch))
          (.fetch globalThis request)))))

(mx/defn ^{:dynamic true :async true} fetch!
  :-
  contracts/PromiseResult
  "Fetches an outbound request map with optional native transport and middleware.
  :codec selects :native (default), :bytes, :text or :json. JSON encodes a supplied
  body and decodes the response. :streaming configures decoded body reading and
  is invalid with :native. :request-init contains native Fetch settings."
  ([request]
   (fetch! request {}))
  ([request
    {:keys [transport codec streaming request-init request-middleware response-middleware
            request-check]
     :or {codec :native streaming {} request-init {}}} :- Options]
   (let [request (if (and (= codec :json) (contains? request :body))
                   (json/encode-body request)
                   request)
         request (if (seq request-init)
                   (update request
                           :fast-twitch.client/options
                           #(assoc (or % {})
                              :request-init (merge (:request-init %) request-init)))
                   request)
         request (await (apply-middleware! request request-middleware))
         result (await (fetch-native! request
                                      (get request :fast-twitch.client/options {})
                                      transport
                                      request-check))
         response (await (apply-middleware! (response/response->map result)
                                            response-middleware))]
     (if (= codec :native)
       response
       (assoc response
         :body
           (when-not (and (= codec :json) (nil? (:body response)))
             (await (readers/read! (:body response)
                                   (assoc streaming :codec codec)))))))))

(defn make-client
  [options]
  (fn [request]
    (fetch! request options)))

(set! fetch! (contracts/instrument 'fast-twitch.client.http/fetch! fetch!))

(set! fetch-native!
      (contracts/instrument 'fast-twitch.client.http/fetch-native! fetch-native!))
