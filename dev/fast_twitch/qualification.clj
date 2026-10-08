(ns fast-twitch.qualification
  (:require [clojure.test :refer [deftest is run-tests]]
            [malli.core :as m]
            [fast-twitch.client.http-options :as http]
            [fast-twitch.util.contracts :as contracts]
            [fast-twitch.macros :as macros]))

(deftest portable-options
  (is (m/validate http/Options {}))
  (let [options {:hostname "localhost" :port 1N}]
    (is (m/validate contracts/TCPConnectOptions options)))
  (is (m/validate http/Options {:request-init {:keepalive false :signal nil}}))
  (is (not (m/validate http/Options {:request-init {:typo true}})))
  (is (not (m/validate http/Options {:call {:headers {}}})))
  (is (m/validate http/Options
                  {:request-init {:credentials "omit" :mode "cors" :redirect "manual"}}))
  (is (not (m/validate http/Options {:request-init {:credentials "guess"}})))
  (is (sequential?
        (macros/serve-deno 'deno 'handler 'host 'port 'on-listen false 'proxy)))
  (is (sequential? (macros/serve-bun 'bun 'handler 'host 'port 'on-listen false 'proxy)))
  (is (sequential?
        (macros/serve-node 'process 'handler 'host 'port 'on-listen false 'proxy))))

(deftest literal-serve-options
  (let [expand (fn [args]
                 (let [form (with-meta (cons 'fast-twitch.macros/serve args)
                              {:file "serve-fixture.cljs" :line 7 :column 3})]
                   (apply @#'macros/serve form {} args)))]
    (doseq [args [[:port -1] [:port 65536] [:port 1.5] [:hostname "127.0.0.1" {:prot 0}]]]
      (try (expand args)
           (is false (str "Accepted invalid literal options " args))
           (catch clojure.lang.ExceptionInfo error
             (let [data (ex-data error)]
               (is (= (if (= :port (first args)) :port :prot) (:option data)))
               (is (= ["serve-fixture.cljs" 7 3]
                      ((juxt :file :line :column) data)))))))
    (doseq [args [[] [:port 0] [:port 65535] [:port 1.0] [:port "0"] [:port nil]
                  [:port false] [:reuse-port false] [:port 0 {:port "0"}]
                  [:port -1 {:port 0}] [:app '{:port -1} :port 0]
                  [:host 42 :hostname "127.0.0.1"]
                  [:port '(throw (Exception. "Do not evaluate at expansion"))]]]
      (is (seq? (expand args))))))

(defn -main
  [& _]
  (let [result (run-tests 'fast-twitch.qualification)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
