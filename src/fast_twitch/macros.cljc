(ns fast-twitch.macros
  "Macros for runtime detection, environment lookup, and native server startup."
  #?(:cljs (:require [fast-twitch.server.runtime.native])))

(defmacro env-var
  "Reads an environment variable from Deno or Node-compatible globals at runtime."
  [v]
  `(let [g# ~'globalThis
         deno-env# (some-> (aget g# "Deno")
                           (aget "env"))
         process-env# (some-> (aget g# "process")
                              (aget "env"))]
     (if deno-env# (.get deno-env# ~v) (when process-env# (aget process-env# ~v)))))

(defmacro current-runtime
  "Expands to a keyword naming the active JavaScript runtime, or nil when unsupported."
  []
  `(let [g# ~'globalThis
         deno# (aget g# "Deno")
         bun# (aget g# "Bun")
         process# (aget g# "process")]
     (cond deno# :deno
           bun# :bun
           (and process#
                (aget process# "versions")
                (aget (aget process# "versions") "node"))
             :node
           :else nil)))

(defn shutdown-registry
  []
  `(fast-twitch.server.runtime.native/shutdown-registry))

(defn serve-deno
  [deno handler hostname port on-listen reuse-port proxy]
  `(fast-twitch.server.runtime.native/serve-deno ~deno
                                                 ~handler
                                                 ~hostname
                                                 ~port
                                                 ~on-listen
                                                 ~reuse-port
                                                 ~proxy))

(defn serve-bun
  [bun handler hostname port on-listen reuse-port proxy]
  `(fast-twitch.server.runtime.native/serve-bun ~bun
                                                ~handler
                                                ~hostname
                                                ~port
                                                ~on-listen
                                                ~reuse-port
                                                ~proxy))

(defn serve-node
  [process handler hostname port on-listen reuse-port proxy]
  `(fast-twitch.server.runtime.native/serve-node ~process
                                                 ~handler
                                                 ~hostname
                                                 ~port
                                                 ~on-listen
                                                 ~reuse-port
                                                 ~proxy))

(defmacro serve
  "Starts a native HTTP server with the existing app/handler defaults.
  Unknown literal option names and invalid numeric literal ports fail at expansion."
  [& {:keys [app handler host hostname port on-listen reuse-port] :as options}]
  (let [allowed #{:app :handler :host :hostname :port :on-listen :reuse-port}
        fail! (fn [option value expected]
                (throw (ex-info (str "serve: invalid " (pr-str option)
                                     " literal " (pr-str value))
                                (merge
                                  (select-keys (meta &form) [:file :line :column])
                                  {:option option :value value :expected expected}))))]
    (doseq [option (keys options)
            :when (and (or (keyword? option)
                           (string? option)
                           (number? option)
                           (boolean? option)
                           (nil? option))
                       (not (contains? allowed option)))]
      (fail! option option allowed))
    (when (and (number? port)
               (not (and (<= 0 port 65535) (== port (long port)))))
      (fail! :port port :integer-port-0-to-65535)))
  `(let [app# ~app
         reuse-port# ~reuse-port]
     (fast-twitch.server.runtime.native/serve!
       (or ~handler (when app# (aget app# "handler")) (when app# (aget app# "fetch")))
       (or ~hostname ~host (when app# (aget app# "hostname")) "127.0.0.1")
       (or ~port (when app# (aget app# "port")) 6464)
       (or ~on-listen (when app# (aget app# "onListen")))
       (if (some? reuse-port#) reuse-port# (when app# (aget app# "reusePort"))))))

(defn shutdown-deno
  [server]
  `(fast-twitch.server.runtime.native/shutdown-deno ~server))

(defn shutdown-bun
  [server force]
  `(fast-twitch.server.runtime.native/shutdown-bun ~server ~force))

(defn shutdown-node
  [server force]
  `(fast-twitch.server.runtime.native/shutdown-node ~server ~force))

(defn shutdown-registered
  [entry force]
  `(fast-twitch.server.runtime.native/shutdown-registered ~entry ~force))

(defmacro shutdown
  "Stops a native server returned by serve; returns its shutdown completion."
  [server & {:keys [force]}]
  `(fast-twitch.server.runtime.native/shutdown! ~server ~force))
