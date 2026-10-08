(ns native-demo.tcp-node
  (:require [native-demo.tcp :as demo]))

(defn -main
  []
  (demo/run! :node))

(set! *main-cli-fn* -main)
