(ns fast-twitch.integration.runner
  (:require
    [cljs.core :refer [await]]
    [malli.core :as m]
    [fast-twitch.util.contracts :as contracts]
    [cljs.test :as test :refer [deftest is run-tests async]]
    [fast-twitch.macros :refer-macros [serve shutdown current-runtime]]
    [fast-twitch.server.sse :as sse]
    [fast-twitch.server.core :as server]
    [fast-twitch.server.websocket :as upgrade]
    [fast-twitch.client.sse.fetch :as sse-fetch]
    [fast-twitch.codecs.sse :as framing]
    [fast-twitch.client.websocket :as websocket]
    [fast-twitch.transports.websocket.connection :as socket]
    [fast-twitch.transports.websocket.listener :as socket-listener]
    [fast-twitch.transports.websocket.protocols :as socket-protocols]
    [fast-twitch.util.websocket.message :as socket-message]
    [fast-twitch.transports.websocket.bun :as bun-socket]
    [fast-twitch.middlewares.head :as head]
    [fast-twitch.middlewares.content-length :as content-length]
    [fast-twitch.client.core :as client]
    [fast-twitch.server.runtime.websocket :as websocket-runtime]
    [fast-twitch.middlewares.timeout :as timeout]
    [fast-twitch.middlewares.params :as params]
    [fast-twitch.middlewares.multipart-params :as multipart]
    [fast-twitch.middlewares.keyword-params :as keyword-params]
    [fast-twitch.middlewares.session :as session]
    [fast-twitch.middlewares.file :as file]
    [fast-twitch.util.streams.readers :as readers]
    [fast-twitch.util.async.cancellation :as cancellation]
    [fast-twitch.client.messaging :as messaging]
    [fast-twitch.transports.tcp.runtime :as tcp]
    [fast-twitch.transports.tcp.node :as tcp-node]
    [fast-twitch.transports.tcp.deno :as tcp-deno]
    [fast-twitch.transports.tcp.bun :as tcp-bun]
    [fast-twitch.client.sse.event-source :as event-source]
    [fast-twitch.middlewares.not-modified :as modified]
    [fast-twitch.routing :as routing]
    [fast-twitch.middlewares.common :as common]
    [fast-twitch.util.http.request :as request]
    [fast-twitch.util.http.response :as response]
    [fast-twitch.client.http :as http]
    [fast-twitch.client.http-options :as http-options]
    [fast-twitch.codecs.json :as json]
    [fast-twitch.integration.public-client-server :as public-consumer]
    [fast-twitch.integration.ring-contracts])
  (:refer-global :only
                 [Request Response Headers URLPattern globalThis Promise Error
                  AbortController TextEncoder ReadableStream Uint8Array DataView Object
                  FormData Blob MessageChannel MessageEvent setTimeout clearTimeout
                  console]))

(deftest production-shapes
  (let [value {:__proto__ {:value 1}
               :constructor "hello"
               :prototype [{:constructor false :__proto__ nil}]}]
    (is (= value (json/decode (json/encode value))))
    (is (= {:constructor "hello"} (json/decode "{\"constructor\":\"hello\"}")))
    (is (thrown? cljs.core/ExceptionInfo (json/encode {:native #js {}}))))
  (doseq [value [9007199254740992 (/ 1 0) (/ 0 0)]]
    (is (thrown? cljs.core/ExceptionInfo (json/encode value))))
  (doseq [text ["9007199254740992" "1e400"]]
    (is (thrown? cljs.core/ExceptionInfo (json/decode text))))
  (is (= [0.5 9007199254740991]
         (json/decode (json/encode [0.5 9007199254740991]))))
  (let [nested (nth (iterate vector nil) 64)]
    (is (= nested (json/decode (json/encode nested))))
    (is (thrown? cljs.core/ExceptionInfo (json/encode [nested])))
    (is (thrown? cljs.core/ExceptionInfo
                 (json/decode (str "[" (json/encode nested) "]")))))
  (try (json/decode (str (apply str (repeat 1048576 " ")) "null"))
       (is false "JSON input size must remain bounded")
       (catch :default error
         (is (= :fast-twitch.codec/limit-exceeded (:code (ex-data error))))
         (is (< (count (pr-str (ex-data error))) 4096))))
  (let [created (atom 0)
        original (aget globalThis "MessageChannel")]
    (try (aset globalThis
               "MessageChannel"
               (fn []
                 (swap! created inc)))
         (is (thrown? cljs.core/ExceptionInfo
                      (messaging/create-channel! {} {:codec :unknown})))
         (is (= 0 @created))
         (finally (aset globalThis "MessageChannel" original))))
  (is (= {:status 200 :headers {} :body "ok"} (routing/response "ok")))
  (is (= {:status 404 :headers {} :body nil} (routing/not-found nil)))
  (is (= {:status 204 :headers {} :body nil} (routing/status 204)))
  (is (routing/response? (routing/response "")))
  (is (not (routing/response? {:ring.websocket/listener {}})))
  (let [native (Request. "http://localhost:8080/a%20b?q=1")
        m (routing/build-request-map native
                                     {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
    (is (= #{:headers :fast-twitch.routing/request :protocol :remote-addr :request-method
             :scheme :server-name :server-port :uri :query-string}
           (set (keys m))))
    (is (= :get (:request-method m)))
    (is (= "/a%20b" (:uri m)))
    (is (identical? native (request/map->request m)))
    (is (= "http://localhost:8080/edited?q=1"
           (.-url (request/map->request (assoc m :uri "/edited")))))
    (is (thrown? Error (routing/build-request-map native {}))))
  (let [native (Response. "body" #js {:status 201 :headers #js {"X-Example" "yes"}})
        m (response/response->map native)]
    (is (identical? native (response/map->response (assoc m :app/extra true))))
    (is (= 202 (.-status (response/map->response (assoc m :status 202)))))
    (is (= #{:status :headers :body} (set (keys (common/fetch-response->ft native)))))
    (let [legacy
            #js {:status 201 :headers (Headers. #js {"X-Example" "yes"}) :body "legacy"}]
      (is (= {:status 201 :headers {"x-example" "yes"} :body "legacy"}
             (common/fetch-response->ft legacy)))
      (is (thrown? cljs.core/ExceptionInfo (response/response->map legacy))))))

(deftest compiler-and-validation
  (is (m/validate http-options/Options {:request-init {:keepalive false}}))
  (is (not (m/validate http-options/Options
                       {:request-init {:keepalive (identity "false")}})))
  (let [{:keys! [present]} {:present false}] (is (false? present)))
  (is (nil? (req! {:present nil} :present)))
  (is (thrown? Error (req! {} :missing)))
  (is (= {:x [false nil 1]} (json/decode (json/encode {:x [false nil 1]}))))
  (is (= {:tenant/id 1 :other/id false}
         (json/decode (json/encode {:tenant/id 1 :other/id false}))))
  (is (thrown? cljs.core/ExceptionInfo (json/encode {1 :invalid})))
  (let [options {:owned? false :mode :default}]
    (is (instance? Promise (readers/read! nil options)))
    (is (identical? (m/validator contracts/CollectOptions)
                    (m/validator contracts/CollectOptions))))
  (try (readers/read! nil {:typo true})
       (is false "Closed Malli options must reject before reader acquisition")
       (catch :default error
         (let [{:keys! [type data]} (ex-data error)
               {:keys! [errors]} data]
           (is (= :malli.core/invalid-input type))
           (is (= [1 :typo] (:in (first errors))))
           (is (= :malli.core/extra-key (:type (first errors)))))))
  (doseq [options [{:owned? nil} {:mode nil} {:chunk-bytes 1048577} {:max-bytes 0}]]
    (is (thrown? cljs.core/ExceptionInfo (readers/read! nil options))))
  (is (= 200 (:status (server/response "ok" {:status 200}))))
  (is (thrown? cljs.core/ExceptionInfo (server/listen! identity {:port -1}))))

(deftest sse-framing
  (doseq [partition [1 2 3 5 11 1000]]
    (let [parser (framing/parser {})
          text "\ufeffid: one\r\ndata: hello\r\ndata: world\r\n\r\nid:\ndata:\n\n"
          events (mapcat #(vec (framing/events! parser (apply str %)))
                   (partition-all partition text))]
      (is (= [{:data "hello\nworld" :event "message" :id "one"}
              {:data "" :event "message" :id ""}]
             (vec events)))))
  (is (thrown? cljs.core/ExceptionInfo
               (framing/encode {:data "ok" :id "injected\nfield"})))
  (is (= [] (vec (framing/events! (framing/parser {}) "data: unfinished")))))

(deftest sse-encoder-value-domains
  (doseq [event [{:data 42} {:data "x" :event "two\nlines"} {:data "x" :id "nul\u0000id"}
                 {:data "x" :retry -1} {:data "x" :retry 9007199254740992}]]
    (is (thrown? cljs.core/ExceptionInfo (framing/encode event))))
  (is (= "data: x\n\n" (framing/encode {:data "x" :event false :retry nil}))))

(defn ^:async deadline
  [promise]
  (let [timer (atom nil)]
    (try (await (Promise.race
                  #js [promise
                       (Promise. (fn [_ reject]
                                   (reset! timer (setTimeout
                                                   #(reject (Error. "Fixture timed out"))
                                                   5000))))]))
         (finally (clearTimeout @timer)))))

(deftest fetch-sse-current-body-and-lifecycle
  (async
    done
    ((^:async fn
      []
      (let [original (.-fetch globalThis)
            request {:url "http://localhost/sse" :request-method :get :headers {}}]
        (try
          (let [text (str
                       "\ufeff: comment\r\nid: one\r\nretry: 12\r\nunknown: ignored\r\n"
                       "data: 😀\r\ndata: two\r\n\r\n"
                         "id: bad\u0000id\nretry: invalid\ndata:\n\n"
                       "id:\nevent: named\ndata: last\n\n"
                         "data: unfinished\ndata: unterminated")
                bytes (.encode (TextEncoder.) text)]
            (doseq [size [1 2 7 (.-byteLength bytes)]]
              (let [offset (atom 0)
                    events (atom [])
                    stream (ReadableStream.
                             #js {:pull (fn [controller]
                                          (if (= @offset (.-byteLength bytes))
                                            (.close controller)
                                            (let [end (min (+ @offset size)
                                                           (.-byteLength bytes))]
                                              (.enqueue controller
                                                        (.slice bytes @offset end))
                                              (reset! offset end))))})]
                (set! (.-fetch globalThis)
                      (fn [_]
                        (Response. stream
                                   #js {:headers #js {"content-type"
                                                        "text/event-stream"}})))
                (await (deadline
                         (client/request! request
                                          {:transport :sse
                                           :on-event (^:async fn
                                                      [event]
                                                      (await (Promise.resolve nil))
                                                      (swap! events conj event))})))
                (is (= [{:data "😀\ntwo" :event "message" :id "one" :retry 12}
                        {:data "" :event "message" :id "one" :retry 12}
                        {:data "last" :event "named" :id "" :retry 12}]
                       @events))
                (is (false? (.-locked stream))))))
          (let [entered (Promise.withResolvers)
                release (Promise.withResolvers)
                events (atom [])
                stream (ReadableStream.
                         #js {:start (fn [controller]
                                       (.enqueue controller
                                                 (.encode
                                                   (TextEncoder.)
                                                   "data: first\n\ndata: second\n\n"))
                                       (.close controller))})]
            (set! (.-fetch globalThis)
                  (fn [_]
                    (Response. stream
                               #js {:headers #js {"content-type" "text/event-stream"}})))
            (let [pending (client/request!
                            request
                            {:transport :sse
                             :on-event (^:async fn
                                        [event]
                                        (swap! events conj (:data event))
                                        (when (= "first" (:data event))
                                          (.resolve entered nil)
                                          (await (.-promise release))))})]
              (try (await (deadline (.-promise entered)))
                   (is (= ["first"] @events))
                   (finally (.resolve release nil)))
              (await (deadline pending))
              (is (= ["first" "second"] @events))
              (is (false? (.-locked stream)))))
          (doseq [[status content-type] [[200 "text/plain"] [500 "text/event-stream"]]]
            (let [cancelled (atom 0)
                  stream (ReadableStream. #js {:cancel #(swap! cancelled inc)})]
              (set! (.-fetch globalThis)
                    (fn [_]
                      (Response. stream
                                 #js {:status status
                                      :headers #js {"content-type" content-type}})))
              (try (await (client/request! request {:transport :sse :on-event identity}))
                   (is false "Invalid SSE responses must reject")
                   (catch :default error
                     (is (= :fast-twitch.sse/invalid-response (:code (ex-data error))))))
              (is (= 1 @cancelled))
              (is (false? (.-locked stream)))))
          (doseq [[limits text] [[{:max-line-bytes 7} "data: é"]
                                 [{:max-event-bytes 2} "data: ab\n\n"]]]
            (let [cancelled (atom 0)
                  events (atom [])
                  stream (ReadableStream.
                           #js {:start #(.enqueue % (.encode (TextEncoder.) text))
                                :cancel #(swap! cancelled inc)})]
              (set! (.-fetch globalThis)
                    (fn [_]
                      (Response. stream
                                 #js {:headers #js {"content-type"
                                                      "text/event-stream"}})))
              (try (await (client/request! request
                                           {:transport :sse
                                            :streaming limits
                                            :on-event #(swap! events conj %)}))
                   (is false "SSE limits must reject before event delivery")
                   (catch :default error
                     (is (= :fast-twitch.stream/limit-exceeded (:code (ex-data error))))))
              (is (empty? @events))
              (is (= 1 @cancelled))
              (is (false? (.-locked stream)))))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (set! (.-fetch globalThis) original)
                   (done))))))))

(deftest callback-discarded-promises
  (async
    done
    ((^:async fn
      []
      (let [unhandled (atom [])
            process (aget globalThis "process")
            node-events? (and process (fn? (aget process "on")))
            record (fn [reason]
                     (swap! unhandled conj reason))
            record-event (fn [event]
                           (.preventDefault event)
                           (record (.-reason event)))]
        (if node-events?
          (.on process "unhandledRejection" record)
          (.addEventListener globalThis "unhandledrejection" record-event))
        (try
          (doseq [mode [:bare :request :response :combined :route]
                  reject? [false true]]
            (let [chosen (Error. "selected response rejection")
                  transformed (atom 0)
                  app (fn [_ respond raise]
                        (respond (if reject?
                                   (Promise.reject chosen)
                                   (routing/response "selected")))
                        (respond (Promise.reject (Error. "discarded response")))
                        (raise (Promise.reject (Error. "discarded error")))
                        (Promise.reject (Error. "discarded handler return")))
                  transform (fn [value _]
                              (swap! transformed inc)
                              value)
                  handler (routing/ft-handler
                            (case mode
                              :bare app
                              :request (common/wrap-request app identity)
                              :response (common/wrap-response app transform)
                              :combined
                                (common/wrap-request-response app identity transform)
                              :route (routing/routes
                                       [{:pattern "/" :async-handler app}]
                                       (fn [_]
                                         (routing/not-found "missing"))))
                            {:async? (not= mode :route)
                             :protocol "HTTP/1.1"
                             :remote-addr "127.0.0.1"})]
              (try
                (let [result (await (handler (Request. "http://localhost/")))]
                  (is (not reject?))
                  (is (= "selected" (await (.text result)))))
                (catch :default error
                  (is reject?)
                  (is (identical? chosen error))))
              (when (#{:response :combined} mode)
                (is (= (if reject? 0 1) @transformed)))))
          (let [returned (Promise.withResolvers)
                respond (atom nil)
                handler (routing/ft-handler
                          (fn [_ deliver _]
                            (reset! respond deliver)
                            (.-promise returned))
                          {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
                native (Request. "http://localhost/")
                result (handler native)]
            (@respond (routing/response "callback before handler return settles"))
            (is (= "callback before handler return settles"
                   (await (.text (await result)))))
            (.reject returned (Error. "handler return rejected after completion")))
          (let [chosen (Error. "first invocation throw")
                handler (routing/ft-handler
                          (fn [& _]
                            (throw chosen))
                          {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
            (try (await (handler (Request. "http://localhost/")))
                 (is false)
                 (catch :default error (is (identical? chosen error)))))
          ;; Yield a native event-loop turn so unhandled-rejection notifications run.
          (await (Promise. (fn [resolve _]
                             (setTimeout resolve 0))))
          (is (empty? @unhandled))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally
            (if node-events?
              (.removeListener process "unhandledRejection" record)
              (.removeEventListener globalThis "unhandledrejection" record-event))
            (done))))))))

(deftest callback-ring-delivery
  (async
    done
    ((^:async fn
      []
      (try (let [native (Request. "http://localhost/")
                 h (routing/ft-handler
                     (fn [_ respond raise]
                       (respond (routing/response "first"))
                       (respond (routing/response "second"))
                       (raise (Error. "late"))
                       :ignored)
                     {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
             (is (= "first" (await (.text (await (h native)))))))
           (doseq [reject? [false true]]
             (let [first-result (Promise.withResolvers)
                   reason (Error. "first callback rejection")
                   h (routing/ft-handler
                       (fn [_ respond raise]
                         (respond (.-promise first-result))
                         (respond (routing/response "duplicate"))
                         (raise (Error. "late callback"))
                         (throw (Error. "late invocation error")))
                       {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
                   request (Request. "http://localhost/")
                   result (h request)]
               (if reject?
                 (.reject first-result reason)
                 (.resolve first-result (routing/response "pending first")))
               (try (let [response (await result)]
                      (is (not reject?))
                      (is (= "pending first" (await (.text response)))))
                    (catch :default error
                      (is reject?)
                      (is (identical? reason error))))))
           (let [h (routing/ft-handler
                     (fn [_ respond _]
                       (setTimeout #(respond (routing/response "later")) 1)
                       :ignored)
                     {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
             (is (= "later"
                    (await (.text (await (deadline (h (Request.
                                                        "http://localhost/")))))))))
           (let [h (routing/ft-handler
                     (fn [_ respond _]
                       (respond {:status 200 :headers {} :body "bad"}))
                     {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
             (is (= 200 (.-status (await (h (Request. "http://localhost/")))))))
           (catch :default e (is false (or (.-stack e) (str e))))
           (finally (done)))))))

(deftest native-http-sse-and-websocket
  (async
    done
    ((^:async fn
      []
      (let [server (atom nil)
            ws (atom nil)
            server-success (atom 0)
            server-failure (atom [])]
        (try
          (let [listened
                  (Promise.
                    (fn [resolve _]
                      (reset! server
                        (serve :handler
                                 (routing/ft-handler
                                   (head/wrap-head
                                     (fn [request]
                                       (case (:uri request)
                                         "/sse" (let [events (atom [{:data "one" :id "1"}
                                                                    {:data "two"
                                                                     :event "named"}])]
                                                  (sse/response
                                                    (fn []
                                                      (let [event (first @events)]
                                                        (swap! events subvec
                                                          (min 1 (count @events)))
                                                        event))))
                                         "/ws" {:ring.websocket/listener
                                                  {:on-message
                                                     (fn [connection message]
                                                       (if (= "async-echo"
                                                              message)
                                                         (socket-protocols/-send-async
                                                           connection
                                                           message
                                                           #(swap! server-success
                                                              inc)
                                                           #(swap! server-failure
                                                              conj
                                                              %))
                                                         (client/send!
                                                           connection
                                                           message)))}}
                                         "/missing" (routing/not-found "missing")
                                         (routing/response "hello"))))
                                   {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
                               :port 0
                               :on-listen resolve))))
                info (await (deadline listened))
                port (aget info "port")
                base (str "http://127.0.0.1:" port)
                m (await (http/fetch!
                           {:url (str base "/") :request-method :get :headers {}}))]
            (is (= 200 (:status m)))
            (is (= "hello" (await (readers/read-body! m {:codec :text}))))
            (is (= 404
                   (:status (await (http/fetch! {:url (str base "/missing")
                                                 :request-method :get
                                                 :headers {}})))))
            (let [events (atom [])]
              (await (deadline (sse-fetch/connect!
                                 {:url (str base "/sse") :request-method :get :headers {}}
                                 {:on-event #(swap! events conj %)})))
              (is (= ["one" "two"] (mapv :data @events))))
            (let [m (await (http/fetch!
                             {:url (str base "/") :request-method :head :headers {}}))]
              (is (= "" (await (readers/read-body! m {:codec :text})))))
            (if (aget globalThis "EventSource")
              (let [source (atom nil)
                    received (Promise.
                               (fn [resolve _]
                                 (reset! source (event-source/connect!
                                                  {:url (str base "/sse")
                                                   :event-types ["named"]
                                                   :on-event
                                                     (fn [event]
                                                       (when (= "named" (:event event))
                                                         (resolve (:data event))))}))))]
                (try (is (= "two" (await (deadline received))))
                     (finally ((:close! @source)))))
              (is (thrown? cljs.core/ExceptionInfo
                           (event-source/connect! {:url (str base "/sse")
                                                   :on-event identity}))))
            (when (or (aget globalThis "Deno") (aget globalThis "Bun"))
              (let [resolve-message (atom nil)
                    received (Promise. (fn [resolve _]
                                         (reset! resolve-message resolve)))]
                (reset! ws (await (websocket/connect!
                                    {:url (str "ws://127.0.0.1:" port "/ws")
                                     :listener {:on-message (fn [_ message]
                                                              (@resolve-message
                                                               message))}})))
                (client/send! @ws "echo")
                (is (= "echo" (await (deadline received))))
                (let [success (atom 0)
                      failure (atom [])
                      received (Promise. (fn [resolve _]
                                           (reset! resolve-message resolve)))]
                  (is (nil? (socket-protocols/-send-async @ws
                                                          "async-echo"
                                                          #(swap! success inc)
                                                          #(swap! failure conj %))))
                  (is (= 1 @success))
                  (is (empty? @failure))
                  (is (= "async-echo" (await (deadline received))))
                  (is (= 1 @server-success))
                  (is (empty? @server-failure)))
                (let [bytes (Uint8Array. #js [99 1 2 3 88])]
                  (doseq [data [(.subarray bytes 1 4) (DataView. (.-buffer bytes) 1 3)]]
                    (let [binary-result (Promise. (fn [resolve _]
                                                    (reset! resolve-message resolve)))]
                      (client/send! @ws {:type :binary :data data})
                      (is (= [1 2 3]
                             (vec (Uint8Array. (await (deadline binary-result))))))))))))
          (catch :default e (is false (or (.-stack e) (str e))))
          (finally (when @ws (socket/close! @ws))
                   (when @server (await (shutdown @server :force true)))
                   (done))))))))

(deftest header-edits-and-async-sends
  (let [calls (atom [])
        native #js {}
        c (socket/connection native
                             {:close (fn [socket code reason]
                                       (swap! calls conj [socket code reason])
                                       :closed)})]
    (doseq [close [socket/close! client/close! server/close!]]
      (reset! calls [])
      (doseq [options [{:code "1000"} {:code 1.5} {:code nil} {:reason false}
                       {:reason nil} {:unknown true}]]
        (is (thrown? cljs.core/ExceptionInfo (close c options)))
        (is (empty? @calls)))
      (is (= :closed (close c)))
      (is (= [[native 1000 ""]] @calls))
      (doseq [code [1001 1011 4000]]
        (is (= :closed (close c {:code code :reason "native reason"})))
        (is (= [native code "native reason"] (last @calls)))))
    (let [reason (Error. "native close failure")
          c (socket/connection native
                               {:close (fn [& _]
                                         (throw reason))})]
      (try (client/close! c {:code 1001})
           (is false)
           (catch :default error (is (identical? reason error))))))
  (let [sent (atom nil)
        c (socket/connection #js {}
                             {:open? (constantly true)
                              :send (fn [_ data]
                                      (reset! sent data))})
        bytes (Uint8Array. #js [99 1 2 3 88])]
    (doseq [data ["text" (.-buffer bytes) (.subarray bytes 1 4)
                  (DataView. (.-buffer bytes) 1 3)]]
      (socket-protocols/-send c data)
      (is (identical? data @sent))
      (socket-protocols/-send c (socket-message/message->map data))
      (is (identical? data @sent)))
    (reset! sent nil)
    (doseq [data [{:type :text :data 1} {:type :binary :data "wrong"}
                  {:type :other :data "wrong"} {:type :text}]]
      (is (thrown? cljs.core/ExceptionInfo (socket-protocols/-send c data)))
      (is (nil? @sent))))
  (doseq [result [-1 3 0]]
    (let [native #js {:readyState 1 :send (constantly result)}
          c (socket/connection native
                               {:open? #(= 1 (.-readyState %)) :send bun-socket/send!})
          success (atom 0)
          failure (atom [])]
      (if (zero? result)
        (is (thrown? cljs.core/ExceptionInfo (socket-protocols/-send c "hello")))
        (is (= result (socket-protocols/-send c "hello"))))
      (is (nil? (socket-protocols/-send-async c
                                              "hello"
                                              #(swap! success inc)
                                              #(swap! failure conj %))))
      (is (= (if (zero? result) 0 1) @success))
      (is (= (if (zero? result) [:fast-twitch.websocket/backpressure] [])
             (mapv #(-> %
                        ex-data
                        :code)
               @failure)))))
  (let [native (Response. nil #js {:headers #js {"x-probe" "old" "set-cookie" "a=1"}})
        m (response/response->map native)]
    (is (= "new"
           (.get (.-headers (response/map->response
                              (assoc-in m [:headers "X-Probe"] "new")))
                 "x-probe")))
    (is (nil? (.get (.-headers (response/map->response
                                 (update m :headers dissoc "x-probe")))
                    "x-probe")))
    (is (identical? native
                    (response/map->response (assoc-in m [:headers "X-Probe"] "old"))))
    (is (thrown? cljs.core/ExceptionInfo
                 (response/map->response (assoc m
                                           :headers {"x-probe" "a" "X-Probe" "b"})))))
  (let [sent (atom 0)
        native #js {:readyState 1
                    :send (fn [_]
                            (swap! sent inc))}
        c (socket/native native)
        success (atom 0)
        failure (atom 0)
        failure-error (atom nil)]
    (is (nil? (socket-protocols/-send-async c
                                            "hello"
                                            #(do (swap! success inc) :ignored)
                                            #(swap! failure inc))))
    (is (= 1 @sent))
    (is (= 1 @success))
    (is (= 0 @failure))
    (is (nil? (socket-protocols/-send-async c
                                            "hello"
                                            #(do (swap! success inc)
                                                 (throw (Error. "callback")))
                                            #(swap! failure inc))))
    (is (= 2 @sent))
    (is (= 2 @success))
    (is (= 0 @failure))
    (aset native "readyState" 3)
    (is (nil? (socket-protocols/-send-async
                c
                "hello"
                #(swap! success inc)
                #(do (swap! failure inc)
                     (reset! failure-error %)
                     (throw (Error. "callback"))))))
    (is (= 2 @sent))
    (is (= 2 @success))
    (is (= 1 @failure))
    (is (= :fast-twitch.websocket/not-open (:code (ex-data @failure-error)))))
  (doseq [error [(Error. "native send") nil false]]
    (let [native #js {:readyState 1
                      :send (fn [_]
                              (throw error))}
          failure (atom [])]
      (is (nil? (socket-protocols/-send-async (socket/native native)
                                              "hello"
                                              #(is false)
                                              #(swap! failure conj %))))
      (is (= 1 (count @failure)))
      (is (identical? error (first @failure)))))
  (let [failure (atom nil)
        c (socket/connection #js {} {:open? (constantly true)})]
    (socket-protocols/-send-async c "hello" #(is false) #(reset! failure %))
    (is (= :fast-twitch.contract/unsupported-capability (:code (ex-data @failure))))))

(deftest ring-map-url-components
  (let [m {:scheme :http
           :server-name "expected.example"
           :server-port 8080
           :uri "/a%20b"
           :query-string "x=1%202"
           :request-method :get
           :headers {}
           :url "http://unexpected.example/b"
           :fast-twitch.routing/request #js {:invalid "ignored"}}]
    (is (= "http://expected.example:8080/a%20b?x=1%202"
           (.-url (common/ft->fetch-request m))))
    (is (= "http://unexpected.example/b"
           (.-url (request/map->request (dissoc m :fast-twitch.routing/request))))))
  (let [native (Request. "http://expected.example/a" #js {:credentials "omit"})
        m (request/request->map native)
        ring (common/ft->fetch-request m)]
    (is (not (identical? native ring)))
    (is (= (.-credentials (Request. "http://expected.example/a")) (.-credentials ring)))
    (is (identical? native (request/map->request m))))
  (doseq [query [nil false "" "q=hello%20world"]]
    (let [m {:scheme :http
             :server-name "expected.example"
             :uri "/a"
             :request-method "get"
             :headers nil
             :query-string query}
          expected (str "http://expected.example/a" (when query (str "?" query)))]
      (is (= expected (.-url (common/ft->fetch-request m))))))
  (let [m {:scheme :http
           :server-name "expected.example"
           :uri "/a"
           :request-method :get
           :headers
             {:x-vector ["left" "right"] :x-null nil :x-false false :x-keyword :value}}
        ring (common/ft->fetch-request m)
        outbound (request/map->request m)]
    (is (= "left,right" (.get (.-headers ring) "x-vector")))
    (is (= "null" (.get (.-headers ring) "x-null")))
    (is (= "false" (.get (.-headers ring) "x-false")))
    (is (= "value" (.get (.-headers ring) "x-keyword")))
    (is (= "left, right" (.get (.-headers outbound) "x-vector")))
    (is (= "" (.get (.-headers outbound) "x-null"))))
  (let [native (Response. "body"
                          #js {:status 202
                               :headers #js [["set-cookie" "a=1"] ["set-cookie" "b=2"]
                                             ["x-probe" "yes"]]})
        ring (common/fetch-response->ft native)
        preserved (response/response->map native)]
    (is (= #{:status :headers :body} (set (keys ring))))
    (is (= 202 (:status ring)))
    (is (= "yes" (get-in ring [:headers "x-probe"])))
    (is (= "b=2" (get-in ring [:headers "set-cookie"])))
    (is (identical? (.-body native) (:body ring)))
    (is (identical? native (:fast-twitch.routing/response preserved)))
    (is (= ["a=1" "b=2"] (get-in preserved [:headers "set-cookie"])))
    (is (identical? native (response/map->response preserved)))))

(deftest request-body-representations
  (async
    done
    ((^:async fn
      []
      (try (let [m {:scheme :http
                    :server-name "expected.example"
                    :uri "/a"
                    :request-method "post"
                    :headers {}}]
             (doseq [body-description [{} {:body nil} {:body false}]]
               (is (nil? (.-body (common/ft->fetch-request (merge m body-description))))))
             (is (= "false" (await (.text (request/map->request (assoc m :body false))))))
             (is (some? (.-body (common/ft->fetch-request (assoc m :body "")))))
             (is (= "" (await (.text (common/ft->fetch-request (assoc m :body ""))))))
             (is (= "0" (await (.text (common/ft->fetch-request (assoc m :body 0))))))
             ;; BodyInit array handling differs by runtime; the native control is the
             ;; current Ring coercion contract, including native rejection.
             (let [read-result (^:async fn
                                [construct]
                                (try {:text (await (.text (construct)))}
                                     (catch :default error {:error-name (.-name error)})))
                   control (await (read-result #(Request. "http://expected.example/a"
                                                          #js {:method "POST"
                                                               :headers #js []
                                                               :body #js ["left" "right"]
                                                               :duplex "half"})))
                   actual (await (read-result #(common/ft->fetch-request
                                                 (assoc m :body ["left" "right"]))))]
               (is (= control actual)))
             (let [bytes (Uint8Array. #js [1 2 3])
                   converted (common/ft->fetch-request (assoc m :body bytes))]
               (is (= [1 2 3] (vec (Uint8Array. (await (.arrayBuffer converted)))))))
             (let [controller (AbortController.)
                   native (Request. "http://expected.example/a"
                                    #js {:method "POST"
                                         :body "origin"
                                         :signal (.-signal controller)})
                   description (request/request->map native)
                   replaced (request/map->request (assoc description
                                                    :body "replacement"))]
               (is (= "replacement" (await (.text replaced))))
               (is (not (.-bodyUsed native)))
               (.abort controller)
               (is (.-aborted (.-signal replaced)))))
           (catch :default error (is false (or (.-stack error) (str error))))
           (finally (done)))))))

(deftest replacements-and-abort-ownership
  (async
    done
    ((^:async fn
      []
      (try
        (let [r (response/response->map (Response. "discarded"
                                                   #js {:headers #js {"etag" "tag"}}))
              h (head/wrap-head (fn [_]
                                  r))
              result (await (h {:request-method :head}))]
          (is (nil? (:body result)))
          (is (= 200 (.-status (response/map->response result)))))
        (let [r (response/response->map (Response. "discarded"
                                                   #js {:headers #js {"etag" "tag"}}))
              h (modified/wrap-not-modified (fn [_]
                                              r))
              result (await (h {:request-method :get :headers {:if-none-match "tag"}}))]
          (is (= 304 (.-status (response/map->response result)))))
        (let [r (Response. "consumed")
              m (response/response->map r)]
          (await (.text r))
          (is (thrown? cljs.core/ExceptionInfo (response/map->response m)))
          (is (= "replacement"
                 (await (.text (response/map->response (assoc m :body "replacement")))))))
        (doseq [owned? [false true]]
          (let [cancelled (atom 0)
                stream (ReadableStream. #js {:cancel #(swap! cancelled inc)})
                controller (AbortController.)
                reason (Error. "expected abort")
                result (readers/reduce! stream
                                        (fn [a _]
                                          a)
                                        :initial
                                        {:owned? owned? :signal (.-signal controller)})]
            (.abort controller reason)
            (try (await result)
                 (is false "Abort must reject")
                 (catch :default error (is (identical? reason error))))
            (is (= (if owned? 1 0) @cancelled))
            (is (false? (.-locked stream)))))
        (doseq [owned? [false true]
                mode [:pre-aborted :reducer-aborted]]
          (let [pulled (atom 0)
                cancelled (atom 0)
                controller (AbortController.)
                reason (Error. "abort before next read")
                stream (ReadableStream. #js {:pull (fn [c]
                                                     (swap! pulled inc)
                                                     (.enqueue c (Uint8Array. #js [1])))
                                             :cancel #(swap! cancelled inc)}
                                        #js {:highWaterMark 0})]
            (when (= mode :pre-aborted) (.abort controller reason))
            (try (await (readers/reduce! stream
                                         (fn [a _]
                                           (.abort controller reason)
                                           a)
                                         0
                                         {:owned? owned? :signal (.-signal controller)}))
                 (is false "Cancellation must reject")
                 (catch :default error (is (identical? reason error))))
            (is (= (if (= mode :pre-aborted) 0 1) @pulled))
            (is (= (if owned? 1 0) @cancelled))
            (is (not (.-locked stream)))))
        (doseq [failed? [false true]]
          (let [controller (AbortController.)
                reason (Error. "aborted during read")
                released? (atom false)
                reader #js {:read (fn []
                                    (.abort controller reason)
                                    (if failed?
                                      (Promise.reject (Error. "native read rejection"))
                                      (Promise.resolve #js {:done false
                                                            :value (Uint8Array. 1)})))
                            :releaseLock #(reset! released? true)}
                stream #js {:getReader (fn []
                                         reader)}]
            (try (await (readers/reduce! stream
                                         (fn [_ _]
                                           (is false "No reducer after abort"))
                                         nil
                                         {:owned? false :signal (.-signal controller)}))
                 (is false "Abort during read must reject")
                 (catch :default error (is (identical? reason error))))
            (is @released?)))
        (catch :default e (is false (or (.-stack e) (str e))))
        (finally (done)))))))

(deftest bounded-signal-and-serving-entrypoints
  (async
    done
    ((^:async fn
      []
      (try
        (let [controller (AbortController.)
              signal (.-signal controller)
              added (atom 0)
              removed (atom 0)
              original-add (.-addEventListener signal)
              original-remove (.-removeEventListener signal)
              count (atom 0)
              stream (ReadableStream. #js {:pull (fn [c]
                                                   (if (< @count 128)
                                                     (do (swap! count inc)
                                                         (.enqueue c
                                                                   (Uint8Array. #js [1])))
                                                     (.close c)))})]
          (set! (.-addEventListener signal)
                (fn [& args]
                  (swap! added inc)
                  (.apply original-add signal (into-array args))))
          (set! (.-removeEventListener signal)
                (fn [& args]
                  (swap! removed inc)
                  (.apply original-remove signal (into-array args))))
          (is (= 128
                 (await (readers/reduce! stream
                                         (fn [n _]
                                           (inc n))
                                         0
                                         {:signal signal}))))
          (is (= 1 @added))
          (is (= 1 @removed))
          (is (false? (.-locked stream))))
        (doseq [mode [:app :handler]]
          (let [native-handler (routing/ft-handler
                                 (routing/routes [{:pattern "/match/:id"
                                                   :handler
                                                     (fn [r]
                                                       (routing/response
                                                         (get-in r [:path-params :id])))}]
                                                 (fn [_]
                                                   (routing/not-found "fallback")))
                                 {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
                listened (Promise.
                           (fn [resolve _]
                             (if (= mode :app)
                               (routing/start-server!
                                 (routing/proxy
                                   {:handler native-handler :port 0 :onListen resolve}))
                               (routing/start-server! native-handler
                                                      {:port 0 :onListen resolve}))))]
            (try (let [info (await (deadline listened))
                       m (await (http/fetch! {:url (str "http://127.0.0.1:"
                                                        (aget info "port")
                                                        "/match/value")
                                              :request-method :get
                                              :headers {}}))]
                   (when-not (aget globalThis "Deno")
                     (is (nil? (Object.getPrototypeOf info)))
                     (is (identical? nil (aget info "missing"))))
                   (is (= "value" (await (readers/read-body! m {:codec :text})))))
                 (finally (await (routing/stop-server! :force true))))))
        (catch :default e (is false (or (.-stack e) (str e))))
        (finally (done)))))))

(deftest native-ports-and-tcp
  (async
    done
    ((^:async fn
      []
      (let [channel (atom nil)
            server (atom nil)
            client (atom nil)
            accepted (atom nil)]
        (try (let [received (Promise. (fn [resolve _]
                                        (reset! channel (messaging/create-channel!
                                                          {:codec :json}
                                                          {:codec :json
                                                           :on-event #(resolve (:data
                                                                                 %))}))))]
               (client/send! (:port1 @channel) {:data [false nil 42]})
               (is (= {:data [false nil 42]} (await (deadline received)))))
             (let [channel2 (messaging/create-channel! {} {})
                   buffer (.-buffer (Uint8Array. #js [1 2 3]))]
               (try (client/send! (:port1 channel2) buffer {:transfer [buffer]})
                    (is (= 0 (.-byteLength buffer)))
                    (is (thrown? cljs.core/ExceptionInfo
                                 (client/send! (:port1 channel2)
                                               #js {}
                                               {:transfer [buffer buffer]})))
                    (finally (client/close! (:port1 channel2))
                             (client/close! (:port2 channel2)))))
             (reset! server
               (await (tcp/listen!
                        {:hostname "127.0.0.1" :port 0}
                        (fn [connection]
                          (reset! accepted connection)
                          ((^:async fn
                            []
                            (try (await (readers/reduce! (:readable connection)
                                                         (^:async fn
                                                          [_ chunk]
                                                          (await (client/send! connection
                                                                               chunk)))
                                                         nil
                                                         {}))
                                 (catch :default _ nil))))))))
             (reset! client (await (deadline (tcp/connect! {:hostname "127.0.0.1"
                                                            :port (:port @server)}))))
             (await (client/send! @client (Uint8Array. #js [4 5 6])))
             (let [reader (.getReader (:readable @client))]
               (try (is (= [4 5 6] (vec (.-value (await (deadline (.read reader)))))))
                    (finally (.releaseLock reader))))
             (catch :default e (is false (or (.-stack e) (str e))))
             (finally (when @client ((:close! @client)))
                      (when @accepted ((:close! @accepted)))
                      (when @server (await ((:close! @server))))
                      (when @channel
                        (client/close! (:port1 @channel))
                        (client/close! (:port2 @channel)))
                      (done))))))))

(deftest cancellation-and-partial-tcp-writes
  (async
    done
    ((^:async fn
      []
      (try
        (let [controller (AbortController.)
              signal (.-signal controller)
              acquire (atom nil)
              disposed (atom [])
              pending (Promise. (fn [resolve _]
                                  (reset! acquire resolve)))
              result (cancellation/await-owned! pending signal #(swap! disposed conj %))
              reason "caller supplied abort reason"
              handle #js {}]
          (.abort controller reason)
          (try (await result)
               (is false "Cancelled acquisition must reject")
               (catch :default error (is (= reason error))))
          (@acquire handle)
          (await (Promise.resolve nil))
          (await (Promise.resolve nil))
          (is (= 1 (count @disposed)))
          (is (identical? handle (first @disposed))))
        (let [controller (AbortController.)
              native-error (Error. "native connect failure")]
          (try (await (cancellation/await-owned! (Promise.reject native-error)
                                                 (.-signal controller)
                                                 #(is false "No handle acquired")))
               (is false "Native rejection must survive")
               (catch :default error (is (identical? native-error error)))))
        (doseq [connector [tcp-node/connect! tcp-deno/connect! tcp-bun/connect!]]
          (let [controller (AbortController.)
                reason "pre-aborted before native effects"]
            (.abort controller reason)
            (try (await (connector
                          {:hostname "127.0.0.1" :port 9 :signal (.-signal controller)}))
                 (is false "Pre-abort must reject")
                 (catch :default error (is (= reason error))))))
        (let [writes (atom [])
              results (atom [2 0 3])
              native #js {:write (fn [bytes]
                                   (swap! writes conj (vec bytes))
                                   (let [n (first @results)]
                                     (swap! results rest)
                                     n))
                          :resume (fn []
                                    nil)
                          :pause (fn []
                                   nil)
                          :end (fn []
                                 nil)
                          :terminate (fn []
                                       nil)}
              adapter (tcp-bun/adapter native {})
              writer (.getWriter (:writable (:connection adapter)))
              pending (.write writer (Uint8Array. #js [10 11 12 13 14]))]
          (try (await (Promise.resolve nil))
               ((:drain adapter))
               ((:drain adapter))
               (await (deadline pending))
               (is (= [[10 11 12 13 14] [12 13 14] [12 13 14]] @writes))
               (await (.write writer (Uint8Array. 0)))
               (is (= 3 (count @writes)))
               (finally (.releaseLock writer))))
        (let [native #js {:write (fn [_]
                                   -1)
                          :resume (fn []
                                    nil)
                          :end (fn []
                                 nil)
                          :terminate (fn []
                                       nil)}
              adapter (tcp-bun/adapter native {})
              writer (.getWriter (:writable (:connection adapter)))]
          (try (try (await (.write writer (Uint8Array. #js [1])))
                    (is false "Closed native writer must reject")
                    (catch :default error
                      (is (= :fast-twitch.tcp/write-failed (:code (ex-data error))))))
               (finally (.releaseLock writer))))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest middleware-settlement-timers-and-sse-bounds
  (async
    done
    ((^:async fn
      []
      (try
        (let [transforms (atom 0)
              handler (common/wrap-response (fn [_ respond raise]
                                              (respond (Response. "first"))
                                              (respond (Response. "duplicate"))
                                              (raise (Error. "late")))
                                            (^:async fn
                                             [m _]
                                             (swap! transforms inc)
                                             (await (Promise.resolve nil))
                                             m))
              native-handler (routing/ft-handler handler
                                                 {:async? true
                                                  :protocol "HTTP/1.1"
                                                  :remote-addr "127.0.0.1"})]
          (is (= "first"
                 (await (.text (await (native-handler (Request. "http://localhost/")))))))
          (is (= 1 @transforms)))
        (let [reason (Error. "response callback rejection")
              raised (atom [])
              handler (common/wrap-response (fn [_ respond _]
                                              (respond (routing/response "value")))
                                            (fn [m _]
                                              m))]
          (await (handler {}
                          (fn [_]
                            (Promise.reject reason))
                          #(swap! raised conj %)))
          (is (= 1 (count @raised)))
          (is (identical? reason (first @raised))))
        (let [reason (Error. "timeout response failed")]
          (try (await (timeout/timeout-promise {}
                                               0
                                               {:error-handler (fn [_]
                                                                 (throw reason))}))
               (is false "Timeout producer must reject")
               (catch :default error (is (identical? reason error))))
          (let [failure (atom nil)
                ready (Promise. (fn [resolve _]
                                  (reset! failure resolve)))
                handler (timeout/wrap-timeout (fn [_ _ _]
                                                nil)
                                              {:timeout-ms 0
                                               :error-handler (fn [_]
                                                                (throw reason))})]
            (handler {}
                     (fn [_]
                       (is false "Failed timeout must raise"))
                     @failure)
            (is (identical? reason (await (deadline ready))))))
        (let [reason (Error. "timeout callback failed")
              raised (atom [])
              handler (timeout/wrap-timeout (fn [_ respond _]
                                              (respond (routing/response "fast")))
                                            {:timeout-ms 1000})]
          (await (handler {}
                          (fn [_]
                            (Promise.reject reason))
                          #(swap! raised conj %)))
          (is (= 1 (count @raised)))
          (is (identical? reason (first @raised))))
        (let [response (Promise.withResolvers)
              expired (Promise.withResolvers)
              timeout-response (Promise.withResolvers)
              request {}
              handler (timeout/wrap-timeout
                        (fn [_]
                          (.-promise response))
                        {:timeout-ms 0
                         :error-handler (fn [_]
                                          (.resolve expired nil)
                                          (.-promise timeout-response))})
              pending (handler request)]
          (await (.-promise expired))
          (.resolve response
                    {:status 200 :body "handler finished during timeout producer"})
          (is (= "handler finished during timeout producer" (:body (await pending))))
          (.resolve timeout-response {:status 503 :body "late timeout"}))
        (let [scheduled (atom #{})
              native-set (.-setTimeout globalThis)
              native-clear (.-clearTimeout globalThis)]
          (set! (.-setTimeout globalThis)
                (fn [callback delay]
                  (let [id (native-set callback delay)]
                    (swap! scheduled conj id)
                    id)))
          (set! (.-clearTimeout globalThis)
                (fn [id]
                  (swap! scheduled disj id)
                  (native-clear id)))
          (try (let [handler (timeout/wrap-timeout (fn [_]
                                                     (Promise.resolve (routing/response
                                                                        "fast")))
                                                   {:timeout-ms 1000})]
                 (is (= "fast" (:body (await (handler {})))))
                 (is (empty? @scheduled)))
               (let [reason (Error. "handler throws")
                     raised (atom nil)
                     handler (timeout/wrap-timeout (fn [_ _ _]
                                                     (throw reason))
                                                   {:timeout-ms 1000})]
                 (handler {}
                          (fn [_]
                            (is false "Must raise"))
                          #(reset! raised %))
                 (is (identical? reason @raised))
                 (is (empty? @scheduled)))
               (finally (doseq [id @scheduled] (native-clear id))
                        (set! (.-setTimeout globalThis) native-set)
                        (set! (.-clearTimeout globalThis) native-clear))))
        (let [text "data: 😀\r\n\r\n"]
          (doseq [split (range (inc (count text)))]
            (let [parser (framing/parser {:max-line-bytes 10})
                  events (into (vec (framing/events! parser (subs text 0 split)))
                               (framing/events! parser (subs text split)))]
              (is (= [{:data "😀" :event "message" :id ""}] events)))))
        (is (thrown? cljs.core/ExceptionInfo
                     (doall (framing/events! (framing/parser {:max-line-bytes 7})
                                             "data: é"))))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest native-byob-reading
  (async
    done
    ((^:async fn
      []
      (try
        (let [sent? (atom false)
              stream (ReadableStream.
                       #js {:type "bytes"
                            :pull (fn [controller]
                                    (if (compare-and-set! sent? false true)
                                      (.enqueue controller (Uint8Array. #js [1 2 3 4 5]))
                                      (let [request (.-byobRequest controller)]
                                        (.close controller)
                                        (when request (.respond request 0)))))})
              chunks (atom [])
              result (await (readers/reduce! stream
                                             (fn [bytes chunk]
                                               (swap! chunks conj (.-byteLength chunk))
                                               (into bytes (vec chunk)))
                                             []
                                             {:mode :byob :chunk-bytes 2}))]
          (is (= [1 2 3 4 5] result))
          (is (= [2 2 1] @chunks))
          (is (not (.-locked stream))))
        (let [calls (atom 0)
              stream #js {:getReader #(swap! calls inc)}]
          (try (await (readers/reduce! stream
                                       (fn [a _]
                                         a)
                                       nil
                                       {:mode :invalid}))
               (is false "Invalid reader mode must reject before acquisition")
               (catch :default error
                 (is (= :malli.core/invalid-input (:type (ex-data error))))))
          (is (= 0 @calls)))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest independent-server-upgrade-lifetimes
  (async
    done
    ((^:async fn
      []
      (let [servers (atom [])
            connections (atom [])]
        (try (when (or (aget globalThis "Deno") (aget globalThis "Bun"))
               (let [start
                       (^:async fn
                        [id]
                        (let [listen (atom nil)
                              ready (Promise. (fn [resolve _]
                                                (reset! listen resolve)))
                              handler (routing/ft-handler
                                        (fn [request]
                                          (if (= "/ws" (:uri request))
                                            {:ring.websocket/listener
                                               {:on-message (fn [c value]
                                                              (client/send!
                                                                c
                                                                (str id value)))}}
                                            (routing/response id)))
                                        {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
                              server (serve :handler handler :port 0 :on-listen @listen)]
                          (swap! servers conj server)
                          {:server server :port (.-port (await (deadline ready)))}))
                     a (await (start "A"))
                     b (await (start "B"))
                     reply (atom nil)
                     replied (Promise. (fn [resolve _]
                                         (reset! reply resolve)))
                     client (await (websocket/connect!
                                     {:url (str "ws://127.0.0.1:" (:port b) "/ws")
                                      :listener {:on-message (fn [_ value]
                                                               (@reply value))}}))]
                 (swap! connections conj client)
                 (await (shutdown (:server a) :force true))
                 (let [response (await (http/fetch!
                                         {:url (str "http://127.0.0.1:" (:port b) "/")
                                          :request-method :get
                                          :headers {}}))]
                   (is (= "B" (await (readers/read-body! response {:codec :text})))))
                 (is (socket-protocols/-open? client))
                 (client/send! client "still-open")
                 (is (= "Bstill-open" (await (deadline replied))))))
             (catch :default error (is false (or (.-stack error) (str error))))
             (finally (doseq [client @connections] (socket/close! client))
                      (doseq [server @servers] (await (shutdown server :force true)))
                      (done))))))))

(deftest native-tcp-half-close-and-peer-eof
  (async
    done
    ((^:async fn
      []
      (let [listening (atom nil)
            client (atom nil)
            accepted (atom nil)
            server-result (atom nil)
            server-done (Promise. (fn [resolve _]
                                    (reset! server-result {:resolve resolve})))]
        (try (reset! listening
               (await (tcp/listen!
                        {:hostname "127.0.0.1" :port 0}
                        (fn [connection]
                          (reset! accepted connection)
                          ((^:async fn
                            []
                            (try (let [bytes (await (readers/read! (req! connection
                                                                         :readable)
                                                                   {:max-bytes 32}))]
                                   (is (= [7 8 9] (vec bytes)))
                                   (await (client/send! connection
                                                        (Uint8Array. #js [9 8 7])))
                                   (await ((req! connection :half-close!)))
                                   ((req! @server-result :resolve) nil))
                                 (catch :default error
                                   ((req! @server-result :resolve) {:error error})))))))))
             (reset! client (await (tcp/connect! {:hostname "127.0.0.1"
                                                  :port (:port @listening)})))
             (await (client/send! @client (Uint8Array. #js [7 8 9])))
             (await ((req! @client :half-close!)))
             (is (= [9 8 7]
                    (vec (await (deadline (readers/read! (req! @client :readable)
                                                         {:max-bytes 32}))))))
             (when-let [error (:error (await (deadline server-done)))] (throw error))
             (catch :default error (is false (or (.-stack error) (str error))))
             (finally (when @client (try ((req! @client :close!)) (catch :default _ nil)))
                      (when @accepted
                        (try ((req! @accepted :close!)) (catch :default _ nil)))
                      (when @listening (await ((req! @listening :close!))))
                      (done))))))))

(deftest absent-body-reader-option-contract
  (async done
         ((^:async fn
           []
           (try
             (let [effects (atom 0)
                   stream #js {:getReader (fn [& _]
                                            (swap! effects inc))}]
               (doseq [body [nil stream]
                       options [{:owned? "yes"} {:mode :invalid} {:chunk-bytes 0}
                                {:signal #js {}}]]
                 (try
                   (await (readers/read! body options))
                   (is false
                       "Invalid reader options must reject for absent and present bodies")
                   (catch :default error
                     (is (= :malli.core/invalid-input (:type (ex-data error)))))))
               (is (= 0 @effects))
               (doseq [options [{} {:owned? true} {:owned? false}
                                {:mode :byob :chunk-bytes 1}]]
                 (is (= 0 (.-byteLength (await (readers/read! nil options)))))))
             (catch :default error (is false (or (.-stack error) (str error))))
             (finally (done)))))))

(deftest middleware-edited-websocket-handshake
  (async
    done
    ((^:async fn
      []
      (try
        (doseq [callback? [false true]
                routed? [false true]
                selected ["old" "new"]
                rebuild? [false true]]
          (let [native (Request. "http://localhost/ws"
                                 #js {:headers #js {"Upgrade" "websocket"
                                                    "Sec-WebSocket-Protocol" "old"}})
                accepted (atom 0)
                seen (atom nil)
                terminal (fn terminal
                           ([m]
                            (reset! seen (get-in m [:headers :sec-websocket-protocol]))
                            {:ring.websocket/listener {}
                             :ring.websocket/protocol selected})
                           ([m respond _]
                            (respond (terminal m))))
                middleware (common/wrap-response
                             (common/wrap-request
                               (if routed?
                                 (routing/routes [{:pattern "/ws"
                                                   (if callback? :async-handler :handler)
                                                     terminal}]
                                                 terminal)
                                 terminal)
                               #(assoc-in % [:headers :sec-websocket-protocol] "new"))
                             (fn [m _]
                               (if rebuild? (into {} m) m)))
                h (routing/ft-handler
                    middleware
                    {:async? callback? :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
            (upgrade/bind! native
                           {:upgrade (fn [_ _]
                                       (swap! accepted inc)
                                       (Response. "incorrect acceptance"))})
            (try (await (h native))
                 (is false "Edited handshake must not accept stale native values")
                 (catch :default error
                   (is (= (if (= selected "old")
                            :fast-twitch.websocket/invalid-protocol
                            :fast-twitch.websocket/unsupported-request-edit)
                          (get-in (ex-data error) [:data :code])))))
            (is (= "new" @seen))
            (is (= 0 @accepted))))
        (doseq [routed? [false true]
                callback? [false true]]
          (let [native (Request. "http://localhost/ws?a=1"
                                 #js {:headers #js {"Upgrade" "websocket"
                                                    "Sec-WebSocket-Protocol" "old"}})
                accepted (atom nil)
                result {:ring.websocket/listener {} :ring.websocket/protocol "old"}
                terminal (fn terminal
                           ([_]
                            result)
                           ([m respond _]
                            (respond (terminal m))))
                handler (if routed?
                          (params/wrap-params (routing/routes [{:pattern "/ws"
                                                                :handler terminal}]
                                                              terminal))
                          terminal)
                h (routing/ft-handler
                    handler
                    {:async? callback? :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
            (upgrade/bind! native
                           {:upgrade (fn [r m]
                                       (reset! accepted [r m])
                                       (Response. "accepted"))})
            (is (= "accepted" (await (.text (await (h native))))))
            (is (identical? native (first @accepted)))
            (is (identical? result (second @accepted)))))
        ;; A route fallback observes the current map even without common wrappers.
        (doseq [callback? [false true]
                promised? [false true]]
          (let [native (Request. "http://localhost/unmatched"
                                 #js {:headers #js {"Upgrade" "websocket"
                                                    "Sec-WebSocket-Protocol" "old"}})
                accepted (atom 0)
                routes (routing/routes []
                                       (fn [_]
                                         (let [m {:ring.websocket/listener {}
                                                  :ring.websocket/protocol "old"}]
                                           (if promised? (Promise.resolve m) m))))
                middleware
                  (fn middleware
                    ([m]
                     (routes (assoc-in m [:headers :sec-websocket-protocol] "new")))
                    ([m respond raise]
                     (routes (assoc-in m [:headers :sec-websocket-protocol] "new")
                             respond
                             raise)))
                h (routing/ft-handler
                    middleware
                    {:async? callback? :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
            (upgrade/bind! native
                           {:upgrade (fn [_ _]
                                       (swap! accepted inc)
                                       (Response. "incorrect"))})
            (try (await (h native))
                 (is false "Fallback must validate its observed handshake")
                 (catch :default error
                   (is (= :fast-twitch.websocket/invalid-protocol
                          (get-in (ex-data error) [:data :code])))))
            (is (= 0 @accepted))))
        (let [native (Response. "callback identity")
              received (atom nil)
              handler (common/wrap-request (fn [_ respond _]
                                             (respond native))
                                           identity)]
          (await (handler {} #(reset! received %) #(throw %)))
          (is (identical? native @received)))
        (let [native (Request. "http://localhost/ws"
                               #js {:headers #js {"Upgrade" "websocket"
                                                  "Sec-WebSocket-Protocol" "old"}})
              accepted (atom 0)
              handler (routing/ft-handler
                        (common/wrap-request
                          (fn [_ respond _]
                            (setTimeout #(respond {:ring.websocket/listener {}
                                                   :ring.websocket/protocol "old"})
                                        1))
                          #(assoc-in % [:headers :sec-websocket-protocol] "new"))
                        {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
          (upgrade/bind! native
                         {:upgrade (fn [_ _]
                                     (swap! accepted inc))})
          (try (await (deadline (handler native)))
               (is false "Edited callback handshake must reject before native upgrade")
               (catch :default error
                 (is (= :fast-twitch.websocket/invalid-protocol
                        (get-in (ex-data error) [:data :code])))))
          (is (= 0 @accepted)))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest validation-before-native-effects
  (async
    done
    ((^:async fn
      []
      (try (let [effects (atom 0)
                 stream #js {:getReader (fn [_]
                                          (swap! effects inc))}]
             (doseq [options [{:owned? "yes"} {:signal #js {}} {:typo true}]]
               (try (await (readers/reduce! stream
                                            (fn [x _]
                                              x)
                                            nil
                                            options))
                    (is false "Invalid reader options must reject")
                    (catch :default error
                      (is (= :malli.core/invalid-input (:type (ex-data error)))))))
             (is (= 0 @effects)))
           (let [effects (atom 0)
                 request {:url "http://unused.example/" :request-method :get :headers {}}
                 transport (fn [_ _]
                             (swap! effects inc)
                             (Response. "unexpected"))]
             (doseq [options [{:transport transport :request-middleware [false]}
                              {:transport transport :response-middleware [false]}]]
               (try (await (http/fetch! request options))
                    (is false "Invalid middleware must reject")
                    (catch :default error
                      (is (= :malli.core/invalid-input (:type (ex-data error)))))))
             (try (await (http/fetch! (assoc request
                                        :fast-twitch.client/options {:request-init
                                                                       {:signal #js {}}})
                                      {:transport transport}))
                  (is false "Invalid signal must reject")
                  (catch :default error
                    (is (= :malli.core/invalid-input (:type (ex-data error))))))
             (try (await (http/fetch! (assoc request
                                        :fast-twitch.client/options
                                          {:request-init {:credentials "guess"}})
                                      {:transport transport}))
                  (is false "Invalid native option domain must reject before Fetch")
                  (catch :default error
                    (is (= :malli.core/invalid-input (:type (ex-data error))))))
             (try (await (http/fetch! request
                                      {:transport transport
                                       :request-middleware
                                         [#(assoc %
                                             :fast-twitch.client/options
                                               {:request-init {:credentials "guess"}})]}))
                  (is false "Middleware-mutated options must reject before native I/O")
                  (catch :default error
                    (is (= :malli.core/invalid-input (:type (ex-data error))))))
             (is (= 0 @effects)))
           (let [original (aget globalThis "WebSocket")
                 effects (atom 0)]
             (set! (.-WebSocket globalThis)
                   (fn [& _]
                     (swap! effects inc)))
             (try (doseq [options [{:url "invalid"} {:url "ws://localhost/#fragment"}
                                   {:url "ws://localhost/" :protocols false}
                                   {:url "ws://localhost/" :listener {:on-message false}}
                                   {:url "ws://localhost/" :on-event false}
                                   {:url "ws://localhost/" :signal #js {}}]]
                    (is (thrown? cljs.core/ExceptionInfo (websocket/connect! options))))
                  (let [controller (AbortController.)
                        reason (Error. "pre-aborted WebSocket")]
                    (.abort controller reason)
                    (try (await (websocket/connect! {:url "ws://localhost/"
                                                     :signal (.-signal controller)}))
                         (is false "Pre-aborted WebSocket must reject")
                         (catch :default error (is (identical? reason error)))))
                  (is (= 0 @effects))
                  (finally (set! (.-WebSocket globalThis) original))))
           (let [effects (atom 0)
                 original (.-fetch globalThis)]
             (set! (.-fetch globalThis)
                   (fn [& _]
                     (swap! effects inc)))
             (try (doseq [limits [{:max-line-bytes 0} {:max-event-bytes 0}
                                  {:max-line-bytes "invalid"}]]
                    (try (await (sse-fetch/connect! {:url "http://unused.example/"
                                                     :request-method :get
                                                     :headers {}}
                                                    {:streaming limits
                                                     :on-event (fn [_]
                                                                 nil)}))
                         (is false "Invalid SSE limits must reject before Fetch")
                         (catch :default error
                           (is (= :malli.core/invalid-input (:type (ex-data error)))))))
                  (is (= 0 @effects))
                  (finally (set! (.-fetch globalThis) original))))
           (catch :default error (is false (or (.-stack error) (str error))))
           (finally (done)))))))

(deftest websocket-close-before-open-cleanup
  (async
    done
    ((^:async fn
      []
      (let [original (aget globalThis "WebSocket")
            subscriptions (atom {})
            closed (atom 0)
            native #js {:readyState 0
                        :addEventListener
                          (fn [type f]
                            (swap! subscriptions update type (fnil conj []) f))
                        :removeEventListener (fn [type f]
                                               (swap! subscriptions update
                                                 type
                                                 #(vec (remove (fn [value]
                                                                 (identical? f value))
                                                         %))))
                        :close #(swap! closed inc)}]
        (set! (.-WebSocket globalThis)
              (fn [_ _]
                native))
        (try (let [opened (atom 0)
                   ended (atom 0)
                   pending (websocket/connect! {:url "ws://localhost/"
                                                :listener {:on-open (fn [_]
                                                                      (swap! opened inc))
                                                           :on-close (fn [_ _ _]
                                                                       (swap! ended
                                                                         inc))}})
                   event #js {:code 1006 :reason "before open"}]
               (doseq [callback (get @subscriptions "close")] (callback event))
               (try (await pending)
                    (is false "Early close must reject")
                    (catch :default error (is (identical? event error))))
               (is (= 0 @opened))
               (is (= 0 @ended))
               (is (= 1 @closed))
               (is (every? empty? (vals @subscriptions))))
             (catch :default error (is false (or (.-stack error) (str error))))
             (finally (set! (.-WebSocket globalThis) original) (done))))))))

(deftest existing-middleware-consumer-paths
  (async
    done
    ((^:async fn
      []
      (try
        (let [seen (atom nil)
              handler (-> (fn [request]
                            (reset! seen request)
                            (routing/response "parsed"))
                          keyword-params/wrap-keyword-params
                          params/wrap-params)
              adapted (routing/ft-handler handler
                                          {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
              native (Request. "http://localhost/form?q=one&q=two"
                               #js {:method "POST"
                                    :headers #js {"content-type"
                                                    "application/x-www-form-urlencoded"}
                                    :body "name=Fast+Twitch&enabled=false"})]
          (is (= "parsed" (await (.text (await (adapted native))))))
          (is (= {:q ["one" "two"] :name "Fast Twitch" :enabled "false"}
                 (:params @seen))))
        (let [store (session/memory-store)
              handler (session/wrap-session
                        (fn [request]
                          (let [n (inc (get-in request [:session :visits] 0))]
                            (assoc (routing/response (str n)) :session {:visits n})))
                        {:store store})
              adapted (routing/ft-handler handler
                                          {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
              first-response (await (adapted (Request. "http://localhost/")))
              cookie (first (.split (.get (.-headers first-response) "set-cookie") ";"))
              next-response (await (adapted (Request. "http://localhost/"
                                                      #js {:headers #js {"cookie"
                                                                           cookie}})))]
          (is (= "1" (await (.text first-response))))
          (is (= "2" (await (.text next-response))))
          (is (= 1 (count @store))))
        (let [seen (atom nil)
              form (FormData.)
              handler (multipart/wrap-multipart-params (fn [request]
                                                         (reset! seen request)
                                                         (routing/response "uploaded")))
              adapted (routing/ft-handler handler
                                          {:protocol "HTTP/1.1"
                                           :remote-addr "127.0.0.1"})]
          (.append form "tag" "one")
          (.append form "tag" "two")
          (.append form
                   "upload"
                   (Blob. #js ["file-body"] #js {:type "text/plain"})
                   "example.txt")
          (is (= "uploaded"
                 (await (.text (await (adapted (Request. "http://localhost/upload"
                                                         #js {:method "POST"
                                                              :body form})))))))
          (let [parsed (req! @seen :multipart-params)
                upload (req! parsed "upload")]
            (is (= ["one" "two"] (req! parsed "tag")))
            (is (= "example.txt" (req! upload :filename)))
            (is (= 9 (req! upload :size)))
            (is (identical? (req! upload :file) (req! upload :tempfile)))
            (is (= "file-body" (await (.text (req! upload :file)))))))
        (let [root "test/fast_twitch/fixtures/static"
              handler (file/wrap-file (fn [_]
                                        (routing/not-found nil))
                                      root)
              adapted (routing/ft-handler handler
                                          {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})
              response (await (adapted (Request. "http://localhost/fixture.txt")))]
          (is (= 200 (.-status response)))
          (is (= "static fixture\n" (await (.text response))))
          (is (= "15" (.get (.-headers response) "content-length")))
          (is (= 404
                 (.-status (await (adapted
                                    (Request.
                                      "http://localhost/%2e%2e%2ffixture.txt")))))))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(deftest sse-producer-disconnect-and-shutdown
  (async
    done
    ((^:async fn
      []
      (let [server (atom nil)
            reader (atom nil)
            release-producer (atom nil)]
        (try (doseq [mode [:disconnect :shutdown]]
               (let [listen (atom nil)
                     ready (Promise. (fn [resolve _]
                                       (reset! listen resolve)))
                     notify-cancel (atom nil)
                     cancelled (Promise. (fn [resolve _]
                                           (reset! notify-cancel resolve)))
                     pending (Promise. (fn [resolve _]
                                         (reset! release-producer resolve)))
                     pulls (atom 0)
                     cancellations (atom 0)
                     handler (routing/ft-handler
                               (fn [_]
                                 (sse/response
                                   (fn []
                                     (if (= 1 (swap! pulls inc)) {:data "first"} pending))
                                   {:on-cancel (fn [_]
                                                 (swap! cancellations inc)
                                                 (@release-producer nil)
                                                 (@notify-cancel nil))}))
                               {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
                 (reset! server (serve :handler handler :port 0 :on-listen @listen))
                 (let [port (.-port (await (deadline ready)))
                       response (await (.fetch globalThis
                                               (str "http://127.0.0.1:" port "/")))]
                   (reset! reader (.getReader (.-body response)))
                   (is (not (.-done (await (deadline (.read @reader))))))
                   (if (= mode :disconnect)
                     (await (.cancel @reader))
                     (await (deadline (shutdown @server :force true))))
                   (await (deadline cancelled))
                   (is (= 1 @cancellations))
                   (.releaseLock @reader)
                   (reset! reader nil)
                   (await (deadline (shutdown @server :force true)))
                   (reset! server nil))))
             (catch :default error (is false (or (.-stack error) (str error))))
             (finally (when @release-producer (@release-producer nil))
                      (when @reader
                        (try (await (.cancel @reader)) (catch :default _ nil))
                        (.releaseLock @reader))
                      (when @server (await (shutdown @server :force true)))
                      (done))))))))

(deftest port-construction-failure-cleanup
  (let [original (aget globalThis "MessageChannel")
        setup-error (Error. "port setup failure")
        cleanup-error (Error. "port cleanup failure")
        make-port
          (fn [phase cleanup-fails?]
            (let [listeners (atom {})
                  closes (atom 0)]
              {:listeners listeners
               :closes closes
               :native #js {:addEventListener (fn [type listener]
                                                (swap! listeners assoc type listener)
                                                (when (= phase type) (throw setup-error)))
                            :removeEventListener (fn [type _]
                                                   (swap! listeners dissoc type)
                                                   (when cleanup-fails?
                                                     (throw cleanup-error)))
                            :start (fn []
                                     (when (= phase :start) (throw setup-error)))
                            :close (fn []
                                     (swap! closes inc)
                                     (when cleanup-fails? (throw cleanup-error)))}}))]
    (try
      (doseq [phase ["message" "messageerror" :start]
              owned? [false true]
              cleanup-fails? [false true]]
        (let [{:keys! [native listeners closes]} (make-port phase cleanup-fails?)]
          (try (messaging/wrap-port native {:owned? owned?})
               (is false)
               (catch :default error (is (identical? setup-error error))))
          (is (empty? @listeners))
          (is (= (if owned? 1 0) @closes))))
      (doseq [phase ["message" "messageerror" :start]
              failing-port [1 2]
              owned1? [false true]
              owned2? [false true]]
        (let [a (make-port (when (= failing-port 1) phase) true)
              b (make-port (when (= failing-port 2) phase) true)]
          (aset globalThis
                "MessageChannel"
                (fn []
                  #js {:port1 (:native a) :port2 (:native b)}))
          (try (messaging/create-channel! {:owned? owned1?} {:owned? owned2?})
               (is false)
               (catch :default error (is (identical? setup-error error))))
          (is (empty? @(:listeners a)))
          (is (empty? @(:listeners b)))
          (is (= 1 @(:closes a)))
          (is (= 1 @(:closes b)))))
      (finally (aset globalThis "MessageChannel" original)))))

(deftest native-port-transfer-and-messageerror-lifecycle
  (async
    done
    ((^:async fn
      []
      (let [channels (atom [])
            moved (atom nil)]
        (try
          (let [receive (atom nil)
                received (Promise. (fn [resolve _]
                                     (reset! receive resolve)))
                channel (messaging/create-channel! {} {:on-event @receive})
                raw (MessageChannel.)
                buffer (.-buffer (Uint8Array. #js [3 2 1]))]
            (swap! channels conj channel)
            (client/send! (req! channel :port1) buffer {:transfer [buffer]})
            (is (= 0 (.-byteLength buffer)))
            (let [event (await (deadline received))]
              (is (identical? (req! (req! channel :port2) :fast-twitch.messaging/port)
                              (:fast-twitch.messaging/port event)))
              (is (= [3 2 1] (vec (Uint8Array. (req! event :data))))))
            (let [receive-port (atom nil)
                  received-port (Promise. (fn [resolve _]
                                            (reset! receive-port resolve)))
                  transferred-channel
                    (messaging/create-channel! {} {:on-event @receive-port})]
              (swap! channels conj transferred-channel)
              (try (client/send! (req! transferred-channel :port1)
                                 #js {:port (.-port2 raw)}
                                 {:transfer [(.-port2 raw)]})
                   (let [event (await (deadline received-port))
                         native (aget (:data event) "port")
                         receive-message (atom nil)
                         received-message (Promise. (fn [resolve _]
                                                      (reset! receive-message resolve)))]
                     (is (identical? native (first (:ports event))))
                     (reset! moved (messaging/wrap-port native
                                                        {:on-event @receive-message}))
                     (.postMessage (.-port1 raw) "through transferred port")
                     (is (= "through transferred port"
                            (:data (await (deadline received-message))))))
                   (finally (.close (.-port1 raw)) (.close (.-port2 raw))))))
          (let [events (atom [])
                channel (messaging/create-channel! {} {:on-event #(swap! events conj %)})
                wrapped (req! channel :port2)
                native (req! wrapped :fast-twitch.messaging/port)
                event (MessageEvent. "messageerror")]
            (swap! channels conj channel)
            (.dispatchEvent native event)
            (is (= :messageerror (:type (first @events))))
            (is (identical? event (:fast-twitch.messaging/event (first @events))))
            (client/close! wrapped)
            (.dispatchEvent native (MessageEvent. "messageerror"))
            (is (= 1 (count @events)))
            (is (thrown? cljs.core/ExceptionInfo
                         (client/send! wrapped "closed")))
            (is (thrown? cljs.core/ExceptionInfo
                         (client/send! (req! channel :port1)
                                       "invalid transfer"
                                       {:transfer [#js {}]}))))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (when @moved (client/close! @moved))
                   (doseq [channel @channels]
                     (client/close! (req! channel :port1))
                     (client/close! (req! channel :port2)))
                   (done))))))))

(deftest native-tcp-pending-abort-and-large-byte-exchange
  (async
    done
    ((^:async fn
      []
      (let [listener (atom nil)
            client (atom nil)
            accepted (atom [])]
        (try
          (reset! listener (await (tcp/listen! {:hostname "127.0.0.1" :port 0}
                                               (fn [connection]
                                                 (swap! accepted conj connection)
                                                 ((req! connection :close!))))))
          (let [controller (AbortController.)
                reason "cancel genuine pending connect"
                pending (tcp/connect! {:hostname "127.0.0.1"
                                       :port (:port @listener)
                                       :signal (.-signal controller)})]
            (.abort controller reason)
            (try (await (deadline pending))
                 (is false "Pending connect must reject original abort")
                 (catch :default error (is (= reason error)))))
          (let [port (:port @listener)]
            (await ((req! @listener :close!)))
            (reset! listener nil)
            (try (await (deadline (tcp/connect! {:hostname "127.0.0.1" :port port})))
                 (is false "Closed listener must refuse connection")
                 (catch :default error
                   (is (some? error))
                   (when (= :node (current-runtime))
                     (is (= "ECONNREFUSED" (.-code error)))))))
          (let [size 262144
                bytes (Uint8Array. size)
                outcome (atom nil)
                server-done (Promise. (fn [resolve _]
                                        (reset! outcome resolve)))]
            (dotimes [i size]
              (aset bytes
                    i
                    (bit-and (bit-xor i (bit-shift-right i 7) (bit-shift-right i 15))
                             255)))
            (reset! listener
              (await (tcp/listen!
                       {:hostname "127.0.0.1" :port 0}
                       (fn [connection]
                         (swap! accepted conj connection)
                         ((^:async fn
                           []
                           (try (let [received (await (readers/read! (req! connection
                                                                           :readable)
                                                                     {:max-bytes size}))]
                                  (await (client/send! connection
                                                       received))
                                  (await ((req! connection :half-close!)))
                                  (@outcome {:size (.-byteLength received)}))
                                (catch :default error (@outcome {:error error})))))))))
            (let [controller (AbortController.)]
              (reset! client (await (tcp/connect! {:hostname "127.0.0.1"
                                                   :port (:port @listener)
                                                   :signal (.-signal controller)})))
              (.abort controller "connect signal no longer owns established socket"))
            (await (client/send! @client bytes))
            (await ((req! @client :half-close!)))
            (let [received (await (deadline (readers/read! (req! @client :readable)
                                                           {:max-bytes size})))
                  result (await (deadline server-done))]
              (when-let [error (:error result)] (throw error))
              (is (= size (req! result :size)))
              (is (= size (.-byteLength received)))
              (is (.every received
                          (fn [byte i]
                            (= byte (aget bytes i)))))))
          (catch :default error (is false (or (.-stack error) (str error))))
          (finally (when @client (try ((req! @client :close!)) (catch :default _ nil)))
                   (doseq [connection @accepted]
                     (try ((req! connection :close!)) (catch :default _ nil)))
                   (when @listener (await ((req! @listener :close!))))
                   (done))))))))

(deftest corrected-native-boundaries
  (async
    done
    ((^:async fn
      []
      (try
        (let [shared (Uint8Array. 1)
              n (atom 0)
              stream (ReadableStream.
                       #js {:pull (fn [controller]
                                    (if (< @n 3)
                                      (do (aset shared 0 (swap! n inc))
                                          (.enqueue controller shared))
                                      (.close controller)))}
                       #js {:highWaterMark 0})]
          (is (= [1 2 3] (vec (await (readers/read! stream {})))))
          (is (false? (.-locked stream))))
        (doseq [server? [false true]]
          (let [controller (AbortController.)
                reason (Error. "public reduction abort")
                stream (ReadableStream.)
                pending
                  (if server?
                    (client/reduce-stream! stream
                                           (fn [n _]
                                             n)
                                           0
                                           {:owned? true :signal (.-signal controller)})
                    (client/reduce-stream! stream
                                           (fn [n _]
                                             n)
                                           0
                                           {:owned? true :signal (.-signal controller)}))]
            (.abort controller reason)
            (try (await pending)
                 (is false "Public reduction must reject")
                 (catch :default error (is (identical? reason error))))
            (is (false? (.-locked stream)))))
        (let [native (MessageChannel.)
              delivered (Promise.withResolvers)
              wrapped (messaging/wrap-port (.-port2 native)
                                           {:codec :json
                                            :on-event #(.resolve delivered %)})]
          (try (.postMessage (.-port1 native) "{invalid-json")
               (let [event (await (deadline (.-promise delivered)))]
                 (is (= :error (:type event)))
                 (is (= :fast-twitch.codec/invalid-json (:code event)))
                 (is (= "SyntaxError" (.-name (:error event))))
                 (is (not (contains? event :data))))
               (finally (client/close! wrapped) (.close (.-port1 native)))))
        (doseq [invoke [(fn []
                          (client/request!
                            {:url "https://secret-url.invalid/token"
                             :headers {"Authorization" "secret-auth"}
                             :body "secret-body"}
                            {:codec :bad}))
                        (fn []
                          (server/response "secret-body" {:status :bad}))
                        (fn []
                          (client/message-channel! {:codec :bad
                                                    :on-event "secret-listener"}))]]
          (try (invoke)
               (is false "Invalid arguments must reject")
               (catch :default error
                 (let [{:keys! [type data]} (ex-data error)
                       printed (pr-str (ex-data error))]
                   (is (= :malli.core/invalid-input type))
                   (is (some? (:operation data)))
                   (is (seq (:errors data)))
                   (is (not (re-find #"secret-|Authorization|:args|:value" printed)))))))
        (let [native (Request. "http://localhost/" #js {:headers #js {"x-review" "old"}})
              edited (assoc-in (request/request->map native) [:headers "X-Review"] "new")]
          (is (= "new" (.get (.-headers (request/map->request edited)) "x-review"))))
        (is (= "2"
               (get-in (content-length/content-length-response {:body "é"} {})
                       [:headers "Content-Length"])))
        (is (= "3"
               (get-in (content-length/content-length-response
                         {:body (.subarray (Uint8Array. 8) 2 5)}
                         {})
                       [:headers "Content-Length"])))
        (let [listening (atom nil)
              ready (Promise.withResolvers)]
          (try (reset! listening
                 (server/listen! (content-length/wrap-content-length
                                   (fn [_]
                                     {:status 200 :body "é"}))
                                 {:port 0 :on-listen #(.resolve ready %)}))
               (let [port (.-port (await (.-promise ready)))
                     m (await (client/request!
                                {:url (str "http://127.0.0.1:" port "/")
                                 :request-method :get
                                 :headers {}}
                                {:codec :bytes}))]
                 (is (= [195 169] (vec (:body m))))
                 (is (= "2" (get-in m [:headers "content-length"]))))
               (finally (when @listening (await (server/close! @listening))))))
        (doseq [wrap [(fn [handler]
                        (common/wrap-request handler identity))
                      (fn [handler]
                        (common/wrap-request-response handler
                                                      identity
                                                      (fn [m _]
                                                        m)))]]
          (let [handler (routing/ft-handler
                          (wrap (fn [_ respond _]
                                  (setTimeout #(respond {:status 200 :body "callback"}) 0)
                                  (Promise.reject (Error. "ignored return"))))
                          {:async? true :protocol "HTTP/1.1" :remote-addr "127.0.0.1"})]
            (is (= "callback"
                   (await (.text (await (handler (Request. "http://localhost/")))))))))
        (doseq [callback? [false true]]
          (let [late (Promise.withResolvers)
                result (Promise.withResolvers)
                cancelled (atom 0)
                stream (ReadableStream. #js {:cancel #(swap! cancelled inc)})
                handler (timeout/wrap-timeout
                          (if callback?
                            (fn [_ respond _]
                              (setTimeout #(respond (.-promise late)) 5))
                            (fn [_]
                              (.-promise late)))
                          {:timeout-ms 0})]
            (if callback?
              (handler {} #(.resolve result %) #(.reject result %))
              (.resolve result (handler {})))
            (is (= 503 (:status (await (.-promise result)))))
            (.resolve late {:status 200 :body stream})
            (await (Promise. (fn [resolve _]
                               (setTimeout resolve 15))))
            (is (= 1 @cancelled))))
        (doseq [timeout-fails? [false true]]
          (let [reason (Error. "race winner rejection")
                response (Promise.withResolvers)
                timeout-value (Promise.withResolvers)
                expired (Promise.withResolvers)
                cancelled (atom 0)
                stream (ReadableStream. #js {:cancel #(swap! cancelled inc)})
                handler (timeout/wrap-timeout
                          (fn [_]
                            (.-promise response))
                          {:timeout-ms 0
                           :error-handler (fn [_]
                                            (.resolve expired nil)
                                            (.-promise timeout-value))})
                request {}
                pending (handler request)]
            (await (.-promise expired))
            (if timeout-fails? (.reject timeout-value reason) (.reject response reason))
            (try (await pending)
                 (is false "First rejection must win")
                 (catch :default error (is (identical? reason error))))
            (if timeout-fails?
              (.resolve response {:status 200 :body stream})
              (.resolve timeout-value {:status 503 :body stream}))
            (await (Promise. (fn [resolve _]
                               (setTimeout resolve 15))))
            (is (= 1 @cancelled))))
        (let [winner-cancelled (atom 0)
              loser-cancelled (atom 0)
              disposed (Promise.withResolvers)
              selected (Promise.withResolvers)
              winner (ReadableStream. #js {:cancel #(swap! winner-cancelled inc)})
              loser (ReadableStream. #js {:cancel (fn []
                                                    (swap! loser-cancelled inc)
                                                    (.resolve disposed nil))})
              handler (timeout/wrap-timeout
                        (fn [_ respond _]
                          (respond {:status 200 :body winner})
                          (respond {:status 200 :body winner})
                          (respond {:status 200 :body loser}))
                        {:timeout-ms 1000})]
          (handler {} #(.resolve selected %) #(.reject selected %))
          (is (identical? winner (:body (await (.-promise selected)))))
          (await (deadline (.-promise disposed)))
          (is (= 0 @winner-cancelled))
          (is (= 1 @loser-cancelled))
          (.cancel winner))
        (let [reason (Error. "winning callback rejection")
              raised (Promise.withResolvers)
              cancelled (atom 0)
              stream (ReadableStream. #js {:cancel #(swap! cancelled inc)})
              handler (timeout/wrap-timeout
                        (fn [_ respond _]
                          (respond (Promise.reject reason))
                          (setTimeout #(respond {:status 200 :body stream}) 5))
                        {:timeout-ms 1000})]
          (handler {}
                   (fn [_]
                     (is false "Rejected winner must raise"))
                   #(.resolve raised %))
          (is (identical? reason (await (.-promise raised))))
          (await (Promise. (fn [resolve _]
                             (setTimeout resolve 15))))
          (is (= 1 @cancelled)))
        (let [events (framing/events!
                       (framing/parser {:max-line-bytes 16 :max-event-bytes 16})
                       (str "data: first\n\n" (apply str (repeat 50 "x"))))]
          (is (= "first" (:data (first events))))
          (is (thrown? cljs.core/ExceptionInfo (doall (rest events)))))
        (let [delivered (atom nil)
              runtime (websocket-runtime/setup :bun identity)
              listener #js {"listener" {:on-ping (fn [connection payload]
                                                   (reset! delivered [connection
                                                                      payload]))}}
              native #js {:data listener :readyState 1}
              handlers (:websocket runtime)]
          ((aget handlers "open") native)
          ((aget handlers "ping") native "native-ping")
          (is (= "native-ping" (second @delivered)))
          (is (satisfies? socket-protocols/Socket (first @delivered))))
        (doseq [async-message? [false true]
                async-error? [false true]]
          (let [seen (atom [])
                reason (Error. "listener failure")
                error-failure (Error. "error listener failure")
                runtime (websocket-runtime/setup :bun identity)
                native #js {:readyState 1
                            :data #js {:listener
                                         {:on-message (fn [connection data]
                                                        (swap! seen conj
                                                          [:message connection data])
                                                        (if async-message?
                                                          (Promise.reject reason)
                                                          (throw reason)))
                                          :on-error (fn [connection error]
                                                      (swap! seen conj
                                                        [:error connection error])
                                                      (if async-error?
                                                        (Promise.reject error-failure)
                                                        (throw error-failure)))}}}
                handlers (:websocket runtime)]
            ((aget handlers "open") native)
            (let [pending ((aget handlers "message") native "payload")]
              (is (= :message (ffirst @seen)))
              (is (= "payload" (nth (first @seen) 2)))
              (await pending)
              (is (= [:message :error] (mapv first @seen)))
              (is (identical? reason (nth (second @seen) 2)))
              (is (identical? (second (first @seen)) (second (second @seen)))))))
        (let [seen (atom [])
              reason (Error. "protocol listener failure")
              connection #js {}
              listener (reify
                         socket-protocols/Listener
                           (on-open [_ c]
                             (swap! seen conj [:open c]))
                           (on-message [_ _ _]
                             (throw reason))
                           (on-pong [_ c data]
                             (swap! seen conj [:pong c data]))
                           (on-error [_ c error]
                             (swap! seen conj [:error c error])
                             (throw (Error. "protocol error listener failure")))
                           (on-close [_ c code reason]
                             (swap! seen conj [:close c code reason]))
                         socket-protocols/PingListener
                           (on-ping [_ c data]
                             (swap! seen conj [:ping c data])))]
          (await (socket-listener/invoke! listener :on-open connection []))
          (await (socket-listener/invoke! listener :on-message connection ["payload"]))
          (await (socket-listener/invoke! listener :on-pong connection ["pong"]))
          (await (socket-listener/invoke! listener :on-ping connection ["ping"]))
          (await (socket-listener/invoke! listener :on-close connection [1000 "done"]))
          (is (= [:open :error :pong :ping :close] (mapv first @seen)))
          (is (every? #(identical? connection (second %)) @seen))
          (is (identical? reason (nth (second @seen) 2)))
          (is (= [1000 "done"] (subvec (last @seen) 2))))
        (catch :default error (is false (or (.-stack error) (str error))))
        (finally (done)))))))

(defonce ^:private run-timer
  (atom nil))

(defmethod test/report [::test/default :end-run-tests]
  [summary]
  (clearTimeout @run-timer)
  (when-let [process (aget globalThis "process")]
    (aset process "exitCode" (if (test/successful? summary) 0 1))))

(defn -main
  [& args]
  (reset! run-timer
    (setTimeout (fn []
                  (.error console "Native fixture run did not finish within 20 seconds")
                  (let [runtime (or (aget globalThis "process") (aget globalThis "Deno"))]
                    (.call (aget runtime "exit") runtime 1)))
                20000))
  (if (= "http-limit-probe" (first args))
    ((^:async fn
      []
      (try (let [result (await (public-consumer/http-limit-probe!))]
             (.log console (str "HTTP limit probe verified: " (pr-str result))))
           (catch :default error
             (.error console error)
             (clearTimeout @run-timer)
             (let [runtime (or (aget globalThis "process") (aget globalThis "Deno"))]
               (.call (aget runtime "exit") runtime 2)))
           (finally (clearTimeout @run-timer)))))
    (run-tests 'fast-twitch.integration.runner
               'fast-twitch.integration.public-client-server
               'fast-twitch.integration.ring-contracts)))

(set! *main-cli-fn* -main)
