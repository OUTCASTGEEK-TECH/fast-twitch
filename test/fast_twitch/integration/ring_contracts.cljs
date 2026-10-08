(ns fast-twitch.integration.ring-contracts
  (:require [cljs.core :refer [await]]
            [cljs.test :refer [deftest is async]]
            [fast-twitch.routing :as routing]
            [fast-twitch.middlewares.common :as common]
            [fast-twitch.middlewares.anti-forgery :as anti-forgery]
            [fast-twitch.util.anti-forgery :refer [anti-forgery-field]])
  (:refer-global :only [Request Response Promise setTimeout]))

(def request-options
  {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"})

(deftest preserved-request-payload-and-ring-response-values
  (async
    done
    ((^:async fn
      []
      (try (let [native (Request. "https://localhost:8443/a%20b?x=1"
                                  #js {:headers #js {"X-Probe" "one"
                                                     "Cookie" "a=1; b=2"}})
                 seen (atom nil)
                 handler (routing/ft-handler (fn [request]
                                               (reset! seen request)
                                               {:status 201
                                                :headers {"x-values" ["one" "two"]
                                                          "set-cookie" ["a=1" "b=2"]}
                                                :body (list "first" " second")})
                                             request-options)
                 response (await (handler native))]
             ;; The approved historical contract is deliberately asserted, not called
             ;; identical to Ring SPEC 1.4's string-keyed request headers.
             (is (= {:x-probe "one" :cookie "a=1; b=2"} (:headers @seen)))
             (is (= :get (:request-method @seen)))
             (is (= :https (:scheme @seen)))
             (is (= "localhost" (:server-name @seen)))
             (is (= 8443 (:server-port @seen)))
             (is (= "/a%20b" (:uri @seen)))
             (is (= "x=1" (:query-string @seen)))
             (is (= "HTTP/1.1" (:protocol @seen)))
             (is (= "127.0.0.1" (:remote-addr @seen)))
             (is (identical? native (:fast-twitch.routing/request @seen)))
             (is (not (contains? @seen :body)))
             (is (= 201 (.-status response)))
             (is (= "one, two" (.get (.-headers response) "x-values")))
             (is (= ["a=1" "b=2"] (vec (.getSetCookie (.-headers response)))))
             (is (= "first second" (await (.text response)))))
           (let [handler (routing/ft-handler (fn [_]
                                               {:status 204 :headers {}})
                                             request-options)]
             (is (nil? (.-body (await (handler (Request. "http://localhost/")))))))
           (catch :default error (is false (str error)))
           (finally (done)))))))

(deftest ring-handler-middleware-and-callback-arities
  (async
    done
    ((^:async fn
      []
      (try (let [seen (atom nil)
                 app (-> (fn [request respond _]
                           (reset! seen request)
                           (setTimeout #(respond {:status 202
                                                  :headers {}
                                                  :body (:query-string request)})
                                       1)
                           {:status 418 :headers {} :body "ignored return"})
                         (common/wrap-request #(assoc % :query-string "changed"))
                         (common/wrap-response
                           (fn [response _]
                             (assoc-in response [:headers "x-middleware"] "yes"))))
                 handler (routing/ft-handler app (assoc request-options :async? true))
                 response (await (handler (Request. "http://localhost/")))]
             (is (= 202 (.-status response)))
             (is (= "changed" (await (.text response))))
             (is (= "yes" (.get (.-headers response) "x-middleware")))
             (is (= "changed" (:query-string @seen))))
           (let [raised (ex-info "Ring callback exception" {})
                 handler (routing/ft-handler (fn [_ _ raise]
                                               (raise raised)
                                               :ignored)
                                             (assoc request-options :async? true))]
             (try (await (handler (Request. "http://localhost/")))
                  (is false)
                  (catch :default actual (is (identical? raised actual)))))
           (catch :default error (is false (str error)))
           (finally (done)))))))

(deftest anti-forgery-public-helper-and-token-readers
  (binding [anti-forgery/*anti-forgery-token* (delay "bound-token")
            anti-forgery/*anti-forgery-param-name* (delay "bound-name")]
    (is (= [:input
            {:type "hidden" :id "bound-name" :name "bound-name" :value "bound-token"}]
           (anti-forgery-field)))
    (is (= "explicit" (get-in (anti-forgery-field {:param-name "explicit"}) [1 :name]))))
  (let [seen (atom nil)
        app (anti-forgery/wrap-anti-forgery
              (fn [request]
                (reset! seen request)
                {:status 200 :headers {} :body (get-in (anti-forgery-field) [1 :value])})
              {:read-token (fn [request _options]
                             (get-in request [:headers "x-custom-token"]))})
        request {:request-method :post
                 :uri "/form"
                 :headers {"x-custom-token" "token"}
                 :session {:anti-forgery-token "token"}}
        response (app request)]
    (is (= 200 (:status response)))
    (is (= "token" (:body response)))
    (is (= "token" (:anti-forgery-token @seen)))
    (is (= "token" (get-in response [:session :anti-forgery-token]))))
  (let [seen (atom nil)
        app (anti-forgery/wrap-anti-forgery
              (fn [_]
                {:status 200 :headers {} :body "ok"})
              {:param-name "custom"
               :read-token (fn [request options]
                             (reset! seen (:param-name options))
                             (get-in request [:form-params (:param-name options)]))})]
    (is (= 200
           (:status (app {:request-method :post
                          :uri "/form"
                          :headers {}
                          :form-params {"custom" "token"}
                          :session {:anti-forgery-token "token"}}))))
    (is (= "custom" @seen)))
  (let [app (anti-forgery/wrap-anti-forgery (fn [_]
                                              {:status 200 :headers {} :body "ok"})
                                            {:token-generator (constantly "fixed")})]
    (is (= 200 (:status (app {:request-method :options :uri "/form" :headers {}}))))
    (is (= 403 (:status (app {:request-method :post :uri "/form" :headers {}}))))))

(deftest anti-forgery-established-error-handler-hooks
  (let [seen (atom nil)
        app (anti-forgery/wrap-anti-forgery
              identity
              {:error-handler (fn [request failure]
                                (reset! seen [(:uri request) failure])
                                {:status 409 :headers {} :body "established"})})
        request {:request-method :post :uri "/form" :headers {}}
        response (atom nil)]
    (is (= 409 (:status (app request))))
    (is (= ["/form" {:reason :invalid-token}] @seen))
    (app request #(reset! response %) #(is false (str %)))
    (is (= 409 (:status @response)))))

(deftest anti-forgery-variadic-partial-and-mixed-callback-payloads
  (let [request {:request-method :post
                 :uri "/form"
                 :headers {}
                 :session {:anti-forgery-token "token"}}
        seen (atom nil)
        variadic-reader (fn [& args]
                          (reset! seen args)
                          "token")
        app (anti-forgery/wrap-anti-forgery (fn [_]
                                              {:status 200 :headers {} :body "ok"})
                                            {:read-token variadic-reader
                                             :param-name "custom"})]
    (is (= 200 (:status (app request))))
    (is (= 2 (count @seen)))
    (is (= "custom" (:param-name (second @seen)))))
  (let [seen (atom nil)
        reader (partial (fn [marker request options]
                          (reset! seen [marker (:uri request) (:param-name options)])
                          "token")
                        :partial)
        app (anti-forgery/wrap-anti-forgery (fn [_]
                                              {:status 200 :headers {} :body "ok"})
                                            {:read-token reader :param-name "custom"})]
    (is (= 200
           (:status (app {:request-method :post
                          :uri "/form"
                          :headers {}
                          :session {:anti-forgery-token "token"}}))))
    (is (= [:partial "/form" "custom"] @seen)))
  (doseq [make-handler [(fn [seen]
                          (fn [& args]
                            (swap! seen conj [(count args) (:reason (second args))])
                            {:status 409 :headers {} :body "variadic"}))
                        (fn [seen]
                          (partial (fn [marker request failure]
                                     (swap! seen conj
                                       [marker (:uri request) (:reason failure)])
                                     {:status 409 :headers {} :body "partial"})
                                   :partial))
                        (fn [seen]
                          (fn
                            ([request failure]
                             (swap! seen conj [:two (:uri request) (:reason failure)])
                             {:status 409 :headers {} :body "two"})
                            ([_ respond _]
                             (swap! seen conj :three)
                             (respond {:status 410 :headers {} :body "three"}))))]]
    (let [seen (atom [])
          app (anti-forgery/wrap-anti-forgery identity
                                              {:error-handler (make-handler seen)})
          request {:request-method :post :uri "/form" :headers {}}
          response (atom nil)]
      (is (= 409 (:status (app request))))
      (app request #(reset! response %) #(is false (str %)))
      (is (= 409 (:status @response)))
      (is (= 2 (count @seen)))
      (is (= (first @seen) (second @seen)))
      (is (= :invalid-token (last (first @seen))))))
  (let [calls (atom 0)
        raised (ex-info "Reader application error" {})
        app (anti-forgery/wrap-anti-forgery identity
                                            {:read-token (fn [_ _]
                                                           (swap! calls inc)
                                                           (throw raised))})]
    (try (app {:request-method :post :uri "/form" :headers {}})
         (is false)
         (catch :default actual (is (identical? raised actual))))
    (is (= 1 @calls))))
