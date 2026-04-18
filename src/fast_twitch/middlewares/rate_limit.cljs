(ns fast-twitch.middlewares.rate-limit
  "Applies token-bucket rate limiting to requests.")

(def default-error-response
  "The default response returned when a client exceeds its rate limit."
  {:status 429
   :headers {"Content-Type" "text/plain"
             "Retry-After" "1"}
   :body "Too Many Requests\n"})

(defn memory-store
  "Creates an atom-backed in-memory rate limit store."
  []
  (atom {}))

(defn client-key
  "Returns the default rate limit key for a request."
  [request]
  (or (:real-ip request)
      (:remote-addr request)
      "unknown"))

(defn- now-ms
  "Returns the current JavaScript timestamp in milliseconds."
  []
  (.now js/Date))

(defn- refill-tokens
  "Refills a bucket based on elapsed time and the configured rate."
  [bucket now rate burst]
  (let [tokens (or (:tokens bucket) burst)
        updated-at (or (:updated-at bucket) now)
        elapsed-seconds (/ (- now updated-at) 1000)
        tokens (min burst (+ tokens (* elapsed-seconds rate)))]
    {:tokens tokens
     :updated-at now}))

(defn- consume-token
  "Consumes one token from a bucket when available."
  [bucket]
  (when (>= (:tokens bucket) 1)
    (update bucket :tokens dec)))

(defn rate-limit-result
  "Returns a map describing whether the request is allowed by the rate limit."
  [store key rate burst]
  (let [now (now-ms)
        result (atom nil)]
    (swap! store
           (fn [buckets]
             (let [bucket (refill-tokens (get buckets key) now rate burst)]
               (if-let [bucket (consume-token bucket)]
                 (do
                   (reset! result {:allowed? true
                                   :key key
                                   :remaining (js/Math.floor (:tokens bucket))})
                   (assoc buckets key bucket))
                 (do
                   (reset! result {:allowed? false
                                   :key key
                                   :remaining 0})
                   (assoc buckets key bucket))))))
    @result))

(defn rate-limit-response
  "Builds the response returned when a request exceeds its rate limit."
  [request result options]
  (if-let [handler (:error-handler options)]
    (handler request result)
    (or (:error-response options) default-error-response)))

(defn rate-limit-request
  "Associates rate limit metadata with a request."
  [request result]
  (assoc request :rate-limit result))

(defn wrap-rate-limit
  "Wraps a handler with per-key token-bucket rate limiting."
  ([handler]
   (wrap-rate-limit handler {}))
  ([handler options]
   (let [store (or (:store options) (memory-store))
         rate (or (:requests-per-second options) (:rate options) 10)
         burst (or (:burst options) (js/Math.ceil rate))
         key-fn (or (:key-fn options) client-key)]
     (fn
       ([request]
        (let [result (rate-limit-result store (key-fn request) rate burst)]
          (if (:allowed? result)
            (handler (rate-limit-request request result))
            (rate-limit-response request result options))))
       ([request respond raise]
        (let [result (rate-limit-result store (key-fn request) rate burst)]
          (if (:allowed? result)
            (handler (rate-limit-request request result) respond raise)
            (respond (rate-limit-response request result options)))))))))
