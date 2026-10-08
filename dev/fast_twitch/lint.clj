(ns fast-twitch.lint
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(defn- execute!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "clj-kondo" args)]
    (print out)
    (binding [*out* *err*] (print err))
    (when-not (zero? exit) (System/exit exit))))

(defn -main
  [& args]
  ;; Analyze the actual pinned dependencies before checking project references.
  ;; This teaches clj-kondo modern cljs.core Vars without excluding missing symbols.
  (try (.mkdirs (io/file ".clj-kondo"))
       (execute! "--lint" (System/getProperty "java.class.path")
                 "--dependencies" "--parallel")
       (apply execute! "--lint" "src" "test" "dev" args)
       (finally (shutdown-agents))))
