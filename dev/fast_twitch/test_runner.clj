(ns fast-twitch.test-runner
  (:require [cljs.build.api :as build]))

(defn -main
  [& args]
  (if (= (first args) "browser")
    (do (build/build "test"
                     {:main 'fast-twitch.integration.browser-runner
                      :output-to "target/browser-tests.js"
                      :output-dir "target/browser-out"
                      :optimizations :simple})
        (build/build "test"
                     {:main 'fast-twitch.integration.browser-server
                      :target :nodejs
                      :output-to "target/browser-server.cjs"
                      :output-dir "target/browser-server-out"
                      :optimizations :simple}))
    (let [advanced? (= (first args) "advanced")]
      (build/build
        "test"
        {:main 'fast-twitch.integration.runner
         :target :nodejs
         :output-to (if advanced? "target/native-advanced.cjs" "target/native-tests.cjs")
         :output-dir (if advanced? "target/advanced-out" "target/cljs-out")
         :optimizations (if advanced? :advanced :simple)
         :infer-externs true
         :elide-asserts false
         :pretty-print true}))))
