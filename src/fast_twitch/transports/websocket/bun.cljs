(ns fast-twitch.transports.websocket.bun)

(defn send!
  [socket data]
  (let [result (.send socket data)]
    (when (= result 0)
      (throw (ex-info "Native WebSocket send dropped"
                      {:code :fast-twitch.websocket/backpressure})))
    ;; -1 is queued, a positive result was admitted; neither proves peer receipt.
    result))
