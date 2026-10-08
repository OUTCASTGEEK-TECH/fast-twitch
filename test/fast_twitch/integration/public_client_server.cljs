(ns fast-twitch.integration.public-client-server
  (:require [cljs.core :refer [await]]
            [cljs.test :refer [deftest is async]]
            [fast-twitch.client.core :as client]
            [fast-twitch.server.core :as server]
            [fast-twitch.server.sse :as sse])
  (:refer-global :only
                 [globalThis Promise Error Response setTimeout clearTimeout ReadableStream
                  AbortController Uint8Array]))

(defn- ^:async deadline
  [promise]
  (let [timer (atom nil)]
    (try (await (Promise.race
                  #js [promise
                       (Promise.
                         (fn [_ reject]
                           (reset! timer (setTimeout
                                           #(reject (Error. "Public consumer timed out"))
                                           5000))))]))
         (finally (clearTimeout @timer)))))

(deftest client-options-precede-native-effects
  (async
    done
    ((^:async fn
      []
      (let [original (aget globalThis "fetch")
            calls (atom 0)
            original-socket (aget globalThis "WebSocket")
            sockets (atom 0)
            request {:url "http://127.0.0.1:1/" :request-method :get :headers {}}]
        (try
          (aset globalThis
                "fetch"
                (fn [& _]
                  (swap! calls inc)))
          (aset globalThis
                "WebSocket"
                (fn [& _]
                  (swap! sockets inc)))
          (doseq [options [{:codec :text :streaming {:max-bytes 0}}
                           {:codec :text :streaming {:owned? "true"}}
                           {:codec :text :streaming {:mode :unknown}}
                           {:codec :text :streaming {:chunk-bytes 0}}
                           {:codec :text :streaming {:signal #js {}}}
                           {:request-init {:credentials "guess"}} {:codec :unknown}
                           {:request-init {:connector identity}}
                           {:codec :text :streaming nil}
                           {:codec :native :streaming {:max-bytes 1}}]]
            (try (await (client/request! request options))
                 (is false (str "Accepted " options))
                 (catch :default error
                   (is (= :malli.core/invalid-input (:type (ex-data error)))))))
          (doseq [options [{:hostname "127.0.0.1" :port 1 :codec :json}
                           {:hostname "127.0.0.1" :port 1 :runtime :unknown}
                           {:hostname "127.0.0.1" :port 1 :connector identity}
                           {:hostname "127.0.0.1" :port 1 :streaming {:mode :byob}}
                           {:hostname "127.0.0.1" :port 1 :streaming {:chunk-bytes 16}}]]
            (try (await (client/connect! (select-keys options [:hostname :port])
                                         (merge {:transport :tcp}
                                                (dissoc options :hostname :port))))
                 (is false)
                 (catch :default error
                   (is (= :malli.core/invalid-input (:type (ex-data error)))))))
          (try (await (client/request! request
                                       {:transport :sse
                                        :streaming {:max-event-bytes 0}
                                        :on-event identity}))
               (is false)
               (catch :default error
                 (is (= :malli.core/invalid-input (:type (ex-data error))))))
          (is (= 0 @calls))
          (doseq [options [{:transport :websocket :codec :json}
                           {:transport :websocket :streaming {:max-bytes 1}}]]
            (try (await (client/connect! {:url "ws://127.0.0.1:1/"} options))
                 (is false)
                 (catch :default error
                   (is (= :malli.core/invalid-input (:type (ex-data error)))))))
          (is (= 0 @sockets))
          (doseq [transport ["secret-url" {"Authorization" "secret-auth"}
                             ["secret-payload"] nil false]
                  invoke [(fn []
                            (client/request! request {:transport transport}))
                          (fn []
                            (client/connect! {:url "ws://localhost/"}
                                             {:transport transport}))
                          (fn []
                            (server/listen! identity {:transport transport}))]]
            (try (await (invoke))
                 (is false "Unsupported transport must reject before native effects")
                 (catch :default error
                   (let [data (ex-data error)]
                     (is (= :fast-twitch.contract/unsupported-transport (:code data)))
                     (is (= [:transport] (:path data)))
                     (is (not (re-find #"secret-|Authorization"
                                       (str (.-message error) (pr-str data)))))))))
          (is (= 0 @calls))
          (is (= 0 @sockets))
          (is (thrown? cljs.core/ExceptionInfo (server/listen! identity {:port -1})))
          (is (thrown? cljs.core/ExceptionInfo (server/response "x" {:codec :bytes})))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (aset globalThis "fetch" original)
                   (aset globalThis "WebSocket" original-socket)
                   (done))))))))

(deftest configured-http-sse-and-websocket
  (async
    done
    ((^:async fn
      []
      (let [serving (atom nil)
            socket (atom nil)
            source (atom nil)]
        (try
          (let [ready (Promise.
                        (fn [resolve _]
                          (reset! serving
                            (server/listen!
                              (^:async fn
                               [request]
                               (case (:uri request)
                                 "/json" (server/response
                                           (await (client/read-body! request
                                                                     {:codec :json
                                                                      :max-bytes 128}))
                                           {:codec :json
                                            :status 201
                                            :headers {"x-configured" "yes"}})
                                 "/sse"
                                   (let [events (atom [{:data "one"}
                                                       {:event "named" :data "two"}])]
                                     (sse/response (fn []
                                                     (let [event (first @events)]
                                                       (swap! events next)
                                                       event))))
                                 "/ws" {:ring.websocket/listener
                                          {:on-message (fn [connection value]
                                                         (client/send! connection value))}
                                        :ring.websocket/protocol "echo"}
                                 (server/response "missing" {:status 404})))
                              {:port 0 :on-listen resolve}))))
                port (.-port (await (deadline ready)))
                base (str "http://127.0.0.1:" port)
                data {"values" [false nil 42]}
                response (await (client/request! {:url (str base "/json")
                                                  :request-method :post
                                                  :headers {}
                                                  :body data}
                                                 {:codec :json
                                                  :streaming {:max-bytes 128}}))]
            (is (= data (:body response)))
            (is (= 201 (:status response)))
            (is (= "yes" (get-in response [:headers "x-configured"])))
            (is (some? (:fast-twitch.routing/response response)))
            (let [events (atom [])]
              (await (deadline (client/request!
                                 {:url (str base "/sse") :request-method :get :headers {}}
                                 {:transport :sse
                                  :streaming {:max-line-bytes 128 :max-event-bytes 256}
                                  :on-event #(swap! events conj %)})))
              (is (= ["one" "two"] (mapv :data @events))))
            (if (aget globalThis "EventSource")
              (let [received (Promise. (fn [resolve _]
                                         (reset! source (client/connect!
                                                          {:url (str base "/sse")}
                                                          {:transport :event-source
                                                           :event-types ["named"]
                                                           :on-event
                                                             #(when (= "named" (:event %))
                                                                (resolve (:data %)))}))))]
                (is (= "two" (await (deadline received)))))
              (is (thrown? cljs.core/ExceptionInfo
                           (client/connect! {:url (str base "/sse")}
                                            {:transport :event-source
                                             :on-event identity}))))
            (when (or (aget globalThis "Bun") (aget globalThis "Deno"))
              (let [received (atom nil)
                    reply (Promise. (fn [resolve _]
                                      (reset! received resolve)))]
                (reset! socket (await (client/connect!
                                        {:url (str "ws://127.0.0.1:" port "/ws")}
                                        {:transport :websocket
                                         :protocols ["echo"]
                                         :listener {:on-message (fn [_ value]
                                                                  (@received value))}})))
                (is (= "echo" (.-protocol (:fast-twitch.websocket/socket @socket))))
                (client/send! @socket "configured echo")
                (is (= "configured echo" (await (deadline reply)))))))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (when @socket (client/close! @socket))
                   (when @source (client/close! @source))
                   (when @serving (await (server/close! @serving {:force true})))
                   (done))))))))

(deftest configured-tcp-streams-and-messaging
  (async
    done
    ((^:async fn
      []
      (let [listening (atom nil)
            connection (atom nil)
            accepted (atom nil)
            channel (atom nil)]
        (try
          (reset! listening
            (await (server/listen!
                     (fn [peer]
                       (reset! accepted peer)
                       ((^:async fn
                         []
                         (try (await (client/send! peer (await (client/receive! peer))))
                              (await (client/half-close! peer))
                              (catch :default error
                                (is false (or (.-stack error) (str error))))))))
                     {:transport :tcp
                      :codec :text
                      :streaming {:max-bytes 32}
                      :hostname "127.0.0.1"
                      :port 0})))
          (reset! connection (await (client/connect! {:hostname "127.0.0.1"
                                                      :port (:port @listening)}
                                                     {:transport :tcp
                                                      :codec :text
                                                      :streaming {:max-bytes 32}})))
          (await (client/send! @connection "configured text"))
          (await (client/half-close! @connection))
          (is (= "configured text" (await (deadline (client/receive! @connection)))))
          (let [received (Promise. (fn [resolve _]
                                     (reset! channel (client/message-channel!
                                                       {:codec :json
                                                        :on-event #(resolve (:data
                                                                              %))}))))
                data {"values" [false nil 42]}]
            (client/send! (:port1 @channel) data)
            (is (= data (await (deadline received)))))
          (let [cancelled (atom 0)
                controller (AbortController.)
                stream (ReadableStream. #js {:pull #(.enqueue % (Uint8Array. #js [1 2 3]))
                                             :cancel #(swap! cancelled inc)}
                                        #js {:highWaterMark 0})]
            (try (await (client/reduce-stream! stream
                                               (fn [n chunk]
                                                 (.abort controller :finished)
                                                 (+ n (.-byteLength chunk)))
                                               0
                                               {:owned? true
                                                :signal (.-signal controller)}))
                 (is false "Abort must reject")
                 (catch :default reason (is (= :finished reason))))
            (is (= 1 @cancelled))
            (is (false? (.-locked stream))))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (when @connection (client/close! @connection))
                   (when @accepted (server/close! @accepted))
                   (when @listening (await (server/close! @listening)))
                   (when @channel
                     (client/close! (:port1 @channel))
                     (client/close! (:port2 @channel)))
                   (done))))))))

(deftest configured-http-codec-and-bounds
  (async
    done
    ((^:async fn
      []
      (let [serving (atom nil)
            handled (atom 0)]
        (try
          (is (thrown?
                cljs.core/ExceptionInfo
                (server/listen!
                  identity
                  {:transport :http :codec :json :streaming {:max-bytes 0} :port 0})))
          (is (thrown? cljs.core/ExceptionInfo
                       (server/listen! identity {:transport :tcp :codec :json :port 0})))
          (is (thrown?
                cljs.core/ExceptionInfo
                (server/listen!
                  identity
                  {:transport :http :codec :native :streaming {:max-bytes 1} :port 0})))
          (let [ready (Promise. (fn [resolve _]
                                  (reset! serving (server/listen!
                                                    (fn [request respond _]
                                                      (swap! handled inc)
                                                      (respond (server/response
                                                                 (:body request)
                                                                 {:status 201}))
                                                      :ignored)
                                                    {:transport :http
                                                     :codec :json
                                                     :streaming {:max-bytes 32}
                                                     :async? true
                                                     :port 0
                                                     :on-listen resolve}))))
                base (str "http://127.0.0.1:" (.-port (await (deadline ready))))
                data {"value" "hello"}
                response (await
                           (client/request!
                             {:url base :request-method :post :headers {} :body data}
                             {:transport :http :codec :json :streaming {:max-bytes 32}}))]
            (is (= data (:body response)))
            (is (= 201 (:status response)))
            (is (= "application/json" (get-in response [:headers "content-type"])))
            (is (= 1 @handled)))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (when @serving (await (server/close! @serving {:force true})))
                   (done))))))))

(defn ^:async http-limit-probe!
  "Separate intentional native HTTP error probe; Bun preserves its native exit status."
  []
  (let [serving (atom nil)
        handled (atom 0)]
    (try (let [ready (Promise. (fn [resolve _]
                                 (reset! serving (server/listen!
                                                   (fn [request respond _]
                                                     (swap! handled inc)
                                                     (respond (server/response
                                                                (:body request))))
                                                   {:transport :http
                                                    :codec :json
                                                    :streaming {:max-bytes 32}
                                                    :async? true
                                                    :port 0
                                                    :on-listen resolve}))))
               base (str "http://127.0.0.1:" (.-port (await (deadline ready))))
               response (await (client/request! {:url base
                                                 :request-method :post
                                                 :headers {}
                                                 :body (apply str (repeat 64 "x"))}
                                                {:codec :text}))]
           (when-not (and (= 500 (:status response))
                          (zero? @handled)
                          (string? (:body response)))
             (throw (ex-info "HTTP limit probe failed"
                             {:response response :handled @handled})))
           {:status (:status response) :handled @handled})
         (finally (when @serving (await (server/close! @serving {:force true})))))))

(deftest incremental-text-reduction
  (async
    done
    ((^:async fn
      []
      (try
        (let [chunks (atom [(Uint8Array. #js [65 240])
                            (.subarray (Uint8Array. #js [0 159 152 0]) 1 3)
                            (Uint8Array. #js [128 66])])
              pulls (atom 0)
              stream (ReadableStream.
                       #js {:pull (fn [controller]
                                    (swap! pulls inc)
                                    (if-let [chunk (first @chunks)]
                                      (do (swap! chunks next) (.enqueue controller chunk))
                                      (.close controller)))}
                       #js {:highWaterMark 0})
              result (await (client/reduce-stream!
                              stream
                              (^:async fn
                               [text chunk]
                               (let [before @pulls]
                                 (await (Promise.resolve nil))
                                 (is (= before @pulls))
                                 (str text chunk)))
                              ""
                              {:codec :text}))]
          (is (= "A😀B" result))
          (is (false? (.-locked stream))))
        (doseq [owned? [true false]]
          (let [cancelled (atom 0)
                stream (ReadableStream.
                         #js {:start (fn [controller]
                                       (.enqueue controller (Uint8Array. #js [240]))
                                       (.close controller))
                              :cancel #(swap! cancelled inc)})]
            (try (await
                   (client/reduce-stream! stream str "" {:codec :text :owned? owned?}))
                 (is false "Incomplete UTF-8 must fail at EOF")
                 (catch :default error (is (= "TypeError" (.-name error)))))
            (is (false? (.-locked stream)))
            ;; A naturally closed stream has no native cancellation callback.
            (is (= 0 @cancelled)))
          (let [cancelled (atom 0)
                stream (ReadableStream.
                         #js {:pull #(.enqueue % (Uint8Array. #js [65]))
                              :cancel #(swap! cancelled inc)}
                         #js {:highWaterMark 0})]
            (is (= "A"
                   (await (client/reduce-stream! stream
                                                 (fn [_ text]
                                                   (reduced text))
                                                 ""
                                                 {:codec :text :owned? owned?}))))
            (is (= (if owned? 1 0) @cancelled))
            (is (false? (.-locked stream)))
            (when-not owned? (await (.cancel stream)))))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest literal-public-request-retains-its-promise
  (async done
         ((^:async fn
           []
           (let [original (aget globalThis "fetch")
                 completion (Promise.withResolvers)]
             (try
               (aset globalThis
                     "fetch"
                     (fn [_]
                       (.-promise completion)))
               (let [pending (client/request!
                               {:url "http://localhost/" :request-method :get :headers {}}
                               {:codec :native})]
                 (is (instance? Promise pending))
                 ;; This resolution must run before the pending call completes.
                 (.resolve completion (Response. "released"))
                 (is (= 200 (:status (await pending)))))
               (aset globalThis
                     "fetch"
                     (fn [_]
                       (Promise.resolve (Response. nil #js {:status 204}))))
               (doseq [codec [:bytes :text :json]]
                 (let [response (await (client/request! {:url "http://localhost/"
                                                         :request-method :get
                                                         :headers {}}
                                                        {:codec codec}))
                       body (:body response)]
                   (is (case codec
                         :bytes (zero? (.-byteLength body))
                         :text (= "" body)
                         :json (nil? body)))))
               (aset globalThis
                     "fetch"
                     (fn [_]
                       (Promise.resolve {:token "secret-response"})))
               (try (await (client/request!
                             {:url "http://localhost/" :request-method :get :headers {}}))
                    (is false "Injected Fetch results must be native responses")
                    (catch :default error
                      (let [{:keys! [type data]} (ex-data error)]
                        (is (= :malli.core/invalid-input type))
                        (is (= :fast-twitch.http/invalid-result (:code data)))
                        (is (nil? (re-find #"secret-response"
                                           (str (.-message error) (pr-str data))))))))
               (catch :default error (is false (or (.-stack error) (str error))))
               (finally (aset globalThis "fetch" original) (done))))))))
