(ns fast-twitch.compiler-fixtures
  (:require [cljs.build.api :as build]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]))

(defn -main
  [& _]
  (let [root "target/compiler-consumers/globals/src"
        file (io/file root "ft_fixture" "globals.cljs")
        output "target/compiler-consumers/globals/consumer.cjs"]
    (.mkdirs (.getParentFile file))
    (spit
      file
      "(ns ft-fixture.globals
                 (:require-macros [fast-twitch.macros :refer [env-var current-runtime]])
                 (:refer-global :only [globalThis console]))
               (defn -main []
                 (assert (= (cond (aget globalThis \"Deno\") :deno
                                  (aget globalThis \"Bun\") :bun :else :node) (current-runtime)))
                 (assert (nil? (env-var \"FAST_TWITCH_ABSENT_GLOBAL_FIXTURE_20261005\")))
                 (.log console \"Macro-only declared globals PASSED\"))
               (set! *main-cli-fn* -main)")
    (build/build root
                 {:target :nodejs
                  :output-dir "target/compiler-consumers/globals/out"
                  :output-to output
                  :optimizations :simple})
    (doseq [command [["node" output] ["bun" output] ["deno" "run" "-A" output]]]
      (let [{:keys [exit out err]} (apply shell/sh command)]
        (print out)
        (when-not (zero? exit)
          (throw (ex-info "Declared global macro consumer failed"
                          {:command command :error err}))))))
  (let
    [source
       "(ns ft-fixture.CONSUMER
       IMPORTS
       (:refer-global :only [Response Promise Object globalThis console]))
     (defn start [handler on-listen calls]
       (let [globalThis nil Number nil]
         (serve :app (do (swap! calls conj :app) nil)
                :handler (do (swap! calls conj :handler) handler)
                :hostname (do (swap! calls conj :hostname) \"127.0.0.1\")
                :port (do (swap! calls conj :port) 0)
                :reuse-port (do (swap! calls conj :reuse-port) false)
                :on-listen (do (swap! calls conj :on-listen) on-listen))))
     (defn stop [server] (shutdown server))
     (defn ^:async -main []
       (try
         (doseq [force? [false true]]
           (let [listen (atom nil)
                 calls (atom [])
                 ready (Promise. (fn [resolve _] (reset! listen resolve)))
                 server (start (fn [_] (Response. \"minimal\")) @listen calls)]
             (try
               (assert (= [:app :reuse-port :handler :hostname :port :on-listen] @calls))
               (let [info (await ready)
                     response (await (.fetch globalThis (str \"http://127.0.0.1:\" (.-port info) \"/\")))]
                 (assert (= \"127.0.0.1\" (.-hostname info)))
                 (when-not (aget globalThis \"Deno\")
                   (assert (nil? (Object.getPrototypeOf info)))
                   (assert (identical? nil (aget info \"missing\"))))
                 (assert (= \"minimal\" (await (.text response)))))
               (finally (await (shutdown server :force force?))
                        (await (stop server))))))
         (.log console \"Minimal direct macro consumer HTTP/listen/shutdown PASSED\")
         (catch :default error (.error console error)
           (when-let [process (aget globalThis \"process\")] (set! (.-exitCode process) 1)))))
     (set! *main-cli-fn* -main)"
     failed? (atom false)]
    (doseq [[consumer imports]
              [["companion"
                "(:require [fast-twitch.macros :refer-macros [serve shutdown]])"]]]
      (let [root (str "target/compiler-consumers/" consumer "/src")
            file (io/file root "ft_fixture" (str consumer ".cljs"))
            output (str "target/compiler-consumers/" consumer "/consumer.cjs")]
        (.mkdirs (.getParentFile file))
        (spit file
              (-> source
                  (string/replace "CONSUMER" consumer)
                  (string/replace "IMPORTS" imports)))
        (build/build root
                     {:target :nodejs
                      :output-dir (str "target/compiler-consumers/" consumer "/out")
                      :output-to output
                      :optimizations :simple})
        (doseq [command [["node" output] ["bun" output] ["deno" "run" "-A" output]]]
          (let [{:keys [exit out err]} (apply shell/sh command)]
            (println consumer (first command) (if (zero? exit) "PASSED" "FAILED"))
            (when (seq out) (print out))
            (when-not (zero? exit)
              (reset! failed? true)
              (println (subs err 0 (min 500 (count err)))))))))
    (when @failed? (println "Conventional macro consumer FAILED") (System/exit 1))))
