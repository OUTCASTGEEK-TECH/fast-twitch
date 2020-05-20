(ns fast-twitch.config
  (:require-macros [fast-twitch.macros :as m])
  (:require [cljs.reader :refer [read-string]]
            [mount.core :as mount :refer [defstate]]
            ["fs" :as fs]))


(declare load-config)

(defstate config :start (load-config (:ft (mount/args))))

(defn- deep-merge [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

;; (defn- deep-merge [v & vs]
;;   (letfn [(rec-merge [v1 v2]
;;             (if (and (map? v1) (map? v2))
;;               (merge-with deep-merge v1 v2)
;;               v2))]
;;     (if (some identity vs)
;;       (reduce #(rec-merge %1 %2) v vs)
;;       (last vs))))

(defn load-config [{:keys [conf-path]
                    :or {conf-path "config.edn"}
                    :as opts}]
  (let [config-path (if-let [CONF-PATH (m/env-var "CONF-PATH")] CONF-PATH conf-path)
        portNumber (if-let [PORT (m/env-var "PORT")] PORT 2020)
        default-conf {:port portNumber}
        config-exists? (fs/existsSync config-path)]
    (if config-exists?
      (let [file-conf (-> (fs/readFileSync config-path "utf8")
                          read-string)
            new-conf (deep-merge default-conf opts file-conf)]
        new-conf)
      default-conf)))

