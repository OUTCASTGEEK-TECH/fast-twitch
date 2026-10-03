(ns build
  (:require [cljs.build.api :as cljs]
            [clojure.edn :as edn]))

(defn -main [& _]
  ;; Compile without browser REPL preloads; Vite bundles the Worker module.
  (cljs/build "src" (edn/read-string (slurp "app.edn"))))
