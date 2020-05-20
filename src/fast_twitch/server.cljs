(ns fast-twitch.server
  (:require-macros [fast-twitch.macros :as m])
  (:require [mount.core :as mount :refer [defstate]]
            [taoensso.timbre :as log]
            [fast-twitch.config :refer [config]]
            [fast-twitch.sugar :as ex]))

(declare start)
(declare stop)

(defstate ft
  :start (start (merge @config
                       (:ft (mount/args))))
  :stop (stop ft))

(defn stop [ft]
  (ex/close (:server @ft)))

(defn start [{:keys [middlewares routes port]
              :as opts}]
  ;;(log/debug "OPTS: " opts)
  (log/info "Listening on PORT: " port)
  (let [app (-> (ex/app)
                (ex/with-middlewares middlewares)
                (ex/with-middleware "/" routes))]
    {:app app
     :server (ex/listen app port)
     :opts opts}))

