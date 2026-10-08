(ns fast-twitch.defaults-consumer
  (:require [cljs.core :refer [await]]
            [fast-twitch.routing :as routing]
            [fast-twitch.middlewares.defaults :as defaults]
            [fast-twitch.middlewares.session :as session])
  (:refer-global :only
                 [Request Response FormData Blob URLSearchParams globalThis console]))

(def request-options
  {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})

(defn expect!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Existing defaults consumer failed"
                    {:expected expected :actual actual}))))

(defn ^:async -main
  []
  (try
    (let [seen (atom nil)
          app (defaults/wrap-defaults (fn [request]
                                        (reset! seen request)
                                        (routing/response "api"))
                                      (-> defaults/api-defaults
                                          (assoc-in [:requests :request-id] true)
                                          (assoc-in [:responses :cache-policy] true)))
          handler (routing/ft-handler app request-options)
          response (await (handler (Request.
                                     "http://localhost/a.txt?q=one&q=two"
                                     #js {:method "POST"
                                          :headers
                                            #js {"content-type"
                                                   "application/x-www-form-urlencoded"}
                                          :body "name=Fast+Twitch"})))]
      (expect! {:q ["one" "two"] :name "Fast Twitch"} (req! @seen :params))
      (expect! 200 (.-status response))
      (expect! "api" (await (.text response)))
      (expect! "nosniff" (.get (.-headers response) "x-content-type-options"))
      (expect! "SAMEORIGIN" (.get (.-headers response) "x-frame-options"))
      (expect! "text/plain; charset=UTF-8" (.get (.-headers response) "content-type"))
      (expect! "no-cache, no-store, must-revalidate"
               (.get (.-headers response) "cache-control"))
      (expect! (req! @seen :request-id) (.get (.-headers response) "x-request-id"))
      (let [head (await (handler (Request. "http://localhost/a.txt"
                                           #js {:method "HEAD"})))]
        (expect! "" (await (.text head)))))
    (let [store (session/memory-store)
          seen (atom nil)
          app (defaults/wrap-defaults
                (fn [request]
                  (reset! seen request)
                  (routing/response (req! request :anti-forgery-token)))
                (assoc-in defaults/site-defaults [:session :store] store))
          handler (routing/ft-handler app request-options)
          initial (await (handler (Request. "http://localhost/site")))
          token (await (.text initial))
          cookie (first (.split (.get (.-headers initial) "set-cookie") ";"))
          invalid (await (handler (Request. "http://localhost/site"
                                            #js {:method "POST"
                                                 :headers #js {"cookie" cookie}
                                                 :body "missing token"})))
          form (FormData.)]
      (expect! 1 (count @store))
      (expect! 403 (.-status invalid))
      (.append form "__anti-forgery-token" token)
      (.append form
               "upload"
               (Blob. #js ["file-body"] #js {:type "text/plain"})
               "example.txt")
      (let [valid (await (handler (Request. "http://localhost/site"
                                            #js {:method "POST"
                                                 :headers #js {"cookie" cookie}
                                                 :body form})))]
        (expect! 200 (.-status valid))
        (expect! token (await (.text valid)))
        (expect! "example.txt" (get-in @seen [:params :upload :filename]))
        (expect! 9 (get-in @seen [:params :upload :size]))))
    (let [app (defaults/wrap-defaults (fn [_ respond raise]
                                        (respond (routing/response "first"))
                                        (respond (routing/response "duplicate"))
                                        (raise :late))
                                      defaults/api-defaults)
          handler (routing/ft-handler app (assoc request-options :async? true))
          response (await (handler (Request. "http://localhost/callback")))]
      (expect! "first" (await (.text response))))
    (.log
      console
      "Default API/site middleware, headers, session, CSRF, multipart and callback consumer PASSED")
    (catch :default error
      (.error console error)
      (when-let [process (aget globalThis "process")] (aset process "exitCode" 1)))))

(set! *main-cli-fn* -main)
