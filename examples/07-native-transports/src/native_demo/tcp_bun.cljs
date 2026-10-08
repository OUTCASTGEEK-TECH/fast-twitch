(ns native-demo.tcp-bun
  (:require [native-demo.tcp :as demo]))

(defn -main
  []
  (demo/run! :bun))

(set! *main-cli-fn* -main)
