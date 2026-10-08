(ns native-demo.tcp-deno
  (:require [native-demo.tcp :as demo]))

(defn -main
  []
  (demo/run! :deno))

(set! *main-cli-fn* -main)
