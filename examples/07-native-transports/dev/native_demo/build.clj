(ns native-demo.build
  (:require [cljs.build.api :as cljs]))

(def modes
  #{"http" "websocket" "sse" "streams" "ports" "tcp-node" "tcp-deno" "tcp-bun"})

(defn -main
  [mode]
  (doseq [mode (if (= mode "all") (sort modes) [mode])]
    (when-not (modes mode) (throw (ex-info "Choose an example" {:modes modes})))
    (cljs/build "src"
                {:main (symbol (str "native-demo." mode))
                 :target :nodejs
                 :output-to (str "target/" mode ".cjs")
                 :output-dir (str "target/" mode)
                 :optimizations :simple})
    (println mode "COMPILED")))
