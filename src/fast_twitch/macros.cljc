(ns fast-twitch.macros
  "Compile-time helpers for runtime detection, environment lookup, and server startup code generation.")

(defmacro env-var
  "Reads an environment variable from Deno or Node-compatible globals at runtime."
  [v]
  `(let [g# ~'globalThis
         deno-env# (some-> (aget g# "Deno") (aget "env"))
         process-env# (some-> (aget g# "process") (aget "env"))]
     (if deno-env#
       (.get deno-env# ~v)
       (when process-env#
         (aget process-env# ~v)))))

(defmacro current-runtime
  "Expands to a keyword naming the active JavaScript runtime, or nil when unsupported."
  []
  `(let [g# ~'globalThis
         deno# (aget g# "Deno")
         bun# (aget g# "Bun")
         process# (aget g# "process")]
     (cond
       deno# :deno
       bun# :bun
       (and process#
            (aget process# "versions")
            (aget (aget process# "versions") "node"))
       :node
       :else
       nil)))

(defn shutdown-registry
  "Builds the shared WeakMap lookup/initialization form for shutdown metadata."
  []
  `(or (aget ~'globalThis "__fastTwitchShutdownRegistry")
       (let [registry# (~'WeakMap.)]
         (aset ~'globalThis "__fastTwitchShutdownRegistry" registry#)
         registry#)))

(defn serve-deno
  "Builds the Deno server bootstrap form for the provided handler and options."
  [deno handler hostname port on-listen reuse-port _proxy]
  `(let [controller# (~'AbortController.)
         opts# (~'clj->js
                (cond-> {:handler ~handler
                         :hostname ~hostname
                         :port ~port
                         :signal (aget controller# "signal")}
                  ~on-listen
                  (assoc :onListen ~on-listen)

                  (some? ~reuse-port)
                  (assoc :reusePort ~reuse-port)))
         server# (.serve ~deno opts#)
         shutdown-server# (fn [_force?#]
                            (let [signal# (aget controller# "signal")
                                  finished# (aget server# "finished")]
                              (when-not (aget signal# "aborted")
                                (.abort controller#))
                              (or finished# (~'Promise.resolve nil))))
         registry# ~(shutdown-registry)]
     (.set registry#
           server#
           (~'js-obj "controller" controller#
                     "runtime" "deno"
                     "shutdown" shutdown-server#))
     server#))

(defn serve-bun
  "Builds the Bun server bootstrap form and normalizes the listen callback payload."
  [bun handler hostname port on-listen reuse-port proxy]
  `(let [controller# (~'AbortController.)
         opts# (~'clj->js
                (cond-> {:fetch ~handler
                         :hostname ~hostname
                         :port ~port}
                  (some? ~reuse-port)
                  (assoc :reusePort ~reuse-port)))
         server# (.serve ~bun opts#)
         stop# (aget server# "stop")
         state# (~'js-obj)
         stop-server# (fn [force?#]
                        (or (aget state# "promise")
                            (let [promise# (.call stop#
                                                  server#
                                                  (boolean force?#))]
                              (aset state# "promise" promise#)
                              promise#)))
         signal# (aget controller# "signal")
         shutdown-server# (fn [force?#]
                            (let [promise# (stop-server# force?#)]
                              (when-not (aget signal# "aborted")
                                (.abort controller#))
                              promise#))
         registry# ~(shutdown-registry)]
     (.addEventListener signal#
                        "abort"
                        (fn []
                          (stop-server# false))
                        (~'clj->js {:once true}))
     (.set registry#
           server#
           (~'js-obj "controller" controller#
                     "runtime" "bun"
                     "shutdown" shutdown-server#))
     (when ~on-listen
       (~on-listen
        (~proxy {:hostname ~hostname
                 :port (aget server# "port")})))
     server#))

(defn serve-node
  "Builds the Node HTTP server bootstrap form, including request and response adaptation."
  [process handler hostname port on-listen reuse-port proxy]
  `(let [controller# (~'AbortController.)
         builtin# (aget ~process "getBuiltinModule")
         http# (builtin# "node:http")
         stream# (builtin# "node:stream")
         server# (.createServer
                  http#
                  (fn [req# res#]
                    (let [node-headers# (aget req# "headers")
                          headers# (~'Headers.)
                          method# (aget req# "method")
                          host# (or (aget node-headers# "host")
                                    (str ~hostname ":" ~port))
                          url# (str "http://" host# (aget req# "url"))
                          has-body?# (not (#{"GET" "HEAD"} method#))
                          init# (~'clj->js
                                 (cond-> {:method method#
                                          :headers headers#}
                                   has-body?#
                                   (assoc :body req# :duplex "half")))]
                      (.forEach (~'Object.entries node-headers#)
                                (fn [entry#]
                                  (let [k# (aget entry# 0)
                                        v# (aget entry# 1)]
                                    (cond
                                      (~'array? v#)
                                      (.forEach v# #(.append headers# k# %))

                                      (some? v#)
                                      (.set headers# k# v#)))))
                      (-> (~'Promise.resolve
                           (~handler (~'Request. url# init#)))
                          (.then
                           (fn [response#]
                             (aset res# "statusCode" (aget response# "status"))
                             (let [headers# (aget response# "headers")
                                   get-set-cookie# (aget headers# "getSetCookie")
                                   set-cookies# (when get-set-cookie#
                                                  (.call get-set-cookie#
                                                         headers#))]
                               (.forEach headers#
                                         (fn [v# k#]
                                           (when-not (= "set-cookie"
                                                        (.toLowerCase k#))
                                             (.setHeader res# k# v#))))
                               (when (and set-cookies#
                                          (pos? (aget set-cookies#
                                                      "length")))
                                 (.setHeader res#
                                             "Set-Cookie"
                                             set-cookies#)))
                             (if-let [body# (aget response# "body")]
                               (.pipe (.fromWeb (aget stream# "Readable") body#) res#)
                               (.end res#))))
                          (.catch
                           (fn [e#]
                             (~'console/error e#)
                             (aset res# "statusCode" 500)
                             (.end res# "Internal Server Error")))))))]
     (let [close# (aget server# "close")
           close-idle-connections# (aget server# "closeIdleConnections")
           close-all-connections# (aget server# "closeAllConnections")
           state# (~'js-obj)
           close-server# (fn [force?#]
                           (or (aget state# "promise")
                               (let [force?# (boolean force?#)
                                     promise#
                                     (~'Promise.
                                      (fn [resolve# reject#]
                                        (try
                                          (.call close#
                                                 server#
                                                 (fn [error#]
                                                   (if error#
                                                     (reject# error#)
                                                     (resolve# nil))))
                                          (cond
                                            (and force?#
                                                 close-all-connections#)
                                            (.call close-all-connections#
                                                   server#)

                                            close-idle-connections#
                                            (.call close-idle-connections#
                                                   server#))
                                          (catch :default error#
                                            (reject# error#)))))]
                                 (aset state# "promise" promise#)
                                 promise#)))
           signal# (aget controller# "signal")
           shutdown-server# (fn [force?#]
                              (let [promise# (close-server# force?#)]
                                (when-not (aget signal# "aborted")
                                  (.abort controller#))
                                promise#))
           registry# ~(shutdown-registry)]
       (.addEventListener signal#
                          "abort"
                          (fn []
                            (close-server# false))
                          (~'clj->js {:once true}))
       (.set registry#
             server#
             (~'js-obj "controller" controller#
                       "runtime" "node"
                       "shutdown" shutdown-server#)))
     (let [listen-opts# (~'clj->js
                         (cond-> {:host ~hostname
                                  :port ~port}
                           (some? ~reuse-port)
                           (assoc :reusePort (boolean ~reuse-port))))]
       (.listen server#
                listen-opts#
                (fn []
                  (when ~on-listen
                    (let [addr# (.address server#)]
                      (~on-listen
                       (~proxy {:hostname ~hostname
                                :port (or (aget addr# "port") ~port)})))))))
     server#))

(defmacro serve
  "Expands to runtime-specific server startup code for Deno, Bun, or Node."
  [& {:keys [app handler host hostname port on-listen reuse-port]}]
  (let [g (gensym "g")
        proxy (gensym "proxy")
        app-sym (gensym "app")
        reuse-port-arg (gensym "reuse-port-arg")
        handler-sym (gensym "handler")
        hostname-sym (gensym "hostname")
        port-sym (gensym "port")
        on-listen-sym (gensym "on-listen")
        reuse-port-sym (gensym "reuse-port")
        deno-sym (gensym "deno")
        bun-sym (gensym "bun")
        process-sym (gensym "process")]
    `(let [~g ~'globalThis
           ~proxy (cljs.proxy/builder)
           ~app-sym ~app
           ~reuse-port-arg ~reuse-port
           ~handler-sym (or ~handler
                            (when ~app-sym (aget ~app-sym "handler"))
                            (when ~app-sym (aget ~app-sym "fetch")))
           ~hostname-sym (or ~hostname
                             ~host
                             (when ~app-sym (aget ~app-sym "hostname"))
                             "127.0.0.1")
           ~port-sym (~'Number
                      (or ~port
                          (when ~app-sym (aget ~app-sym "port"))
                          6464))
           ~on-listen-sym (or ~on-listen
                              (when ~app-sym (aget ~app-sym "onListen")))
           ~reuse-port-sym (if (some? ~reuse-port-arg)
                             ~reuse-port-arg
                             (when ~app-sym (aget ~app-sym "reusePort")))
           ~deno-sym (aget ~g "Deno")
           ~bun-sym (aget ~g "Bun")
           ~process-sym (aget ~g "process")]
       (when-not ~handler-sym
         (throw (~'Error. "serve requires :handler or :app with handler/fetch")))
       (cond
         ~deno-sym
         ~(serve-deno deno-sym handler-sym hostname-sym port-sym
                      on-listen-sym reuse-port-sym proxy)

         ~bun-sym
         ~(serve-bun bun-sym handler-sym hostname-sym port-sym
                     on-listen-sym reuse-port-sym proxy)

         (and ~process-sym
              (aget ~process-sym "versions")
              (aget (aget ~process-sym "versions") "node"))
         ~(serve-node process-sym handler-sym hostname-sym port-sym
                      on-listen-sym reuse-port-sym proxy)

         :else
         (throw (~'Error. "No supported server runtime found"))))))

(defn shutdown-deno
  "Builds Deno HttpServer shutdown code using the documented shutdown method."
  [server]
  `(let [server# ~server
         shutdown# (when server#
                     (aget server# "shutdown"))]
     (if shutdown#
       (.call shutdown# server#)
       (throw (~'Error. "shutdown requires a Deno server returned by serve")))))

(defn shutdown-bun
  "Builds Bun server shutdown code using server.stop()."
  [server force]
  `(let [server# ~server
         stop# (when server#
                 (aget server# "stop"))]
     (if stop#
       (.call stop# server# (boolean ~force))
       (throw (~'Error. "shutdown requires a Bun server returned by serve")))))

(defn shutdown-node
  "Builds Node http.Server shutdown code using close and connection cleanup."
  [server force]
  `(let [server# ~server
         force?# (boolean ~force)
         close# (when server#
                  (aget server# "close"))
         close-idle-connections# (when server#
                                   (aget server# "closeIdleConnections"))
         close-all-connections# (when server#
                                  (aget server# "closeAllConnections"))]
     (if close#
       (~'Promise.
        (fn [resolve# reject#]
          (try
            (.call close#
                   server#
                   (fn [error#]
                     (if error#
                       (reject# error#)
                       (resolve# nil))))
            (cond
              (and force?# close-all-connections#)
              (.call close-all-connections# server#)

              close-idle-connections#
              (.call close-idle-connections# server#))
            (catch :default error#
              (reject# error#)))))
       (throw (~'Error. "shutdown requires a Node http.Server returned by serve")))))

(defn shutdown-registered
  "Builds shutdown code for servers registered by serve with an AbortController."
  [entry force]
  `(let [entry# ~entry
         shutdown# (when entry#
                     (aget entry# "shutdown"))]
     (if shutdown#
       (.call shutdown# entry# (boolean ~force))
       (throw (~'Error. "shutdown requires a server returned by serve")))))

(defmacro shutdown
  "Expands to runtime-specific server shutdown code for a server returned by serve.
  Returns a promise that resolves when the runtime reports shutdown completion."
  [server & {:keys [force]}]
  (let [server-sym (gensym "server")
        registry-sym (gensym "registry")
        entry-sym (gensym "entry")
        shutdown-sym (gensym "shutdown")
        stop-sym (gensym "stop")
        close-sym (gensym "close")]
    `(let [~server-sym ~server
           ~registry-sym (aget ~'globalThis "__fastTwitchShutdownRegistry")
           ~entry-sym (when (and ~server-sym ~registry-sym)
                        (.get ~registry-sym ~server-sym))
           ~shutdown-sym (when ~server-sym
                           (aget ~server-sym "shutdown"))
           ~stop-sym (when ~server-sym
                       (aget ~server-sym "stop"))
           ~close-sym (when ~server-sym
                        (aget ~server-sym "close"))]
       (cond
         ~entry-sym
         ~(shutdown-registered entry-sym force)

         ~shutdown-sym
         ~(shutdown-deno server-sym)

         ~stop-sym
         ~(shutdown-bun server-sym force)

         ~close-sym
         ~(shutdown-node server-sym force)

         :else
         (throw (~'Error. "shutdown requires a server returned by serve"))))))
