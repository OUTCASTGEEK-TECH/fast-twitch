(ns fast-twitch.integration.browser-server
  (:require [fast-twitch.macros :refer-macros [serve]]
            [fast-twitch.routing :as routing]
            [fast-twitch.server.sse :as sse]
            [fast-twitch.transports.websocket.protocols :as socket-protocols])
  (:refer-global :only [globalThis console]))

(defn -main
  []
  (serve
    :port 17464
    :handler
      (routing/ft-handler
        (fn [request]
          (case (:uri request)
            "/http" (routing/response "hello")
            "/sse" (let [events (atom [{:data "one"} {:data "two" :event "named"}])]
                     (sse/response #(let [event (first @events)]
                                      (swap! events subvec (min 1 (count @events)))
                                      event)))
            "/ws" {:ring.websocket/listener {:on-message
                                               (fn [c message]
                                                 (socket-protocols/-send c message))}}
            "/browser-tests.js" (assoc-in (routing/response (.file
                                                              (aget globalThis "Bun")
                                                              "target/browser-tests.js"))
                                  [:headers "content-type"]
                                  "text/javascript")
            (assoc-in
              (routing/response
                "<!doctype html><html><body>Native browser fixture<script src='/browser-tests.js'></script></body></html>")
              [:headers "content-type"]
              "text/html")))
        {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})))

(set! *main-cli-fn* -main)
