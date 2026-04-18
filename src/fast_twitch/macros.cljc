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

(defn serve-deno
  "Builds the Deno server bootstrap form for the provided handler and options."
  [deno handler hostname port on-listen reuse-port _proxy]
  `(.serve ~deno
     (~'clj->js
      (cond-> {:handler ~handler
               :hostname ~hostname
               :port ~port}
        ~on-listen
        (assoc :onListen ~on-listen)

        (some? ~reuse-port)
        (assoc :reusePort ~reuse-port)))))

(defn serve-bun
  "Builds the Bun server bootstrap form and normalizes the listen callback payload."
  [bun handler hostname port on-listen reuse-port proxy]
  `(let [opts# (~'clj->js
                (cond-> {:fetch ~handler
                         :hostname ~hostname
                         :port ~port}
                  (some? ~reuse-port)
                  (assoc :reusePort ~reuse-port)))
         server# (.serve ~bun opts#)]
     (when ~on-listen
       (~on-listen
        (~proxy {:hostname ~hostname
                 :port (aget server# "port")})))
     server#))

(defn serve-node
  "Builds the Node HTTP server bootstrap form, including request and response adaptation."
  [process handler hostname port on-listen reuse-port proxy]
  `(let [builtin# (aget ~process "getBuiltinModule")
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
