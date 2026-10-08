(ns fast-twitch.server.runtime.native
  (:require [cljs.core :refer [await]]
            [cljs.proxy :as proxy]
            [fast-twitch.server.runtime.websocket :as websocket])
  (:refer-global :only
                 [globalThis AbortController Error Headers Number Object Promise Request
                  WeakMap console]))

(defn shutdown-registry
  []
  (or (aget globalThis "__fastTwitchShutdownRegistry")
      (let [registry (WeakMap.)]
        (aset globalThis "__fastTwitchShutdownRegistry" registry)
        registry)))

(defn- register!
  [server controller runtime shutdown]
  (.set (shutdown-registry)
        server
        #js {:controller controller :runtime runtime :shutdown shutdown}))

(defn serve-deno
  [deno handler hostname port on-listen reuse-port _proxy]
  (let [{:keys! [handler cleanup!]} (websocket/setup :deno handler)
        controller (AbortController.)
        options
          #js
           {:handler handler :hostname hostname :port port :signal (.-signal controller)}
        _ (when on-listen (aset options "onListen" on-listen))
        _ (when (some? reuse-port) (aset options "reusePort" reuse-port))
        server (.serve deno options)
        shutdown (fn [_force?]
                   (cleanup!)
                   (when-not (.-aborted (.-signal controller)) (.abort controller))
                   (or (.-finished server) (Promise.resolve nil)))]
    (register! server controller "deno" shutdown)
    server))

(defn serve-bun
  [bun handler hostname port on-listen reuse-port proxy]
  (let [{:keys! [handler cleanup! websocket]} (websocket/setup :bun handler)
        controller (AbortController.)
        options #js {:fetch handler :hostname hostname :port port :websocket websocket}
        _ (when (some? reuse-port) (aset options "reusePort" reuse-port))
        server (.serve bun options)
        state #js {}
        stop (fn [force?]
               (or (aget state "promise")
                   (let [promise (.stop server (boolean force?))]
                     (aset state "promise" promise)
                     promise)))
        shutdown (fn [force?]
                   (cleanup!)
                   (let [promise (stop force?)]
                     (when-not (.-aborted (.-signal controller)) (.abort controller))
                     promise))]
    (.addEventListener (.-signal controller)
                       "abort"
                       (fn []
                         (stop false))
                       #js {:once true})
    (register! server controller "bun" shutdown)
    (when on-listen (on-listen (proxy {:hostname hostname :port (.-port server)})))
    server))

(defn shutdown-node
  [server force]
  (let [force? (boolean force)
        close (when server (aget server "close"))
        close-idle (when server (aget server "closeIdleConnections"))
        close-all (when server (aget server "closeAllConnections"))]
    (if close
      (let [completion (Promise.withResolvers)]
        (try (.call close
                    server
                    (fn [error]
                      (if error (.reject completion error) (.resolve completion nil))))
             (cond (and force? close-all) (.call close-all server)
                   close-idle (.call close-idle server))
             (catch :default error (.reject completion error)))
        (.-promise completion))
      (throw (Error. "shutdown requires a Node http.Server returned by serve")))))

(defn serve-node
  [process handler hostname port on-listen reuse-port proxy]
  (let [controller (AbortController.)
        builtin (aget process "getBuiltinModule")
        http (builtin "node:http")
        stream (builtin "node:stream")
        server
          (.createServer
            http
            (fn [request response]
              (let [node-headers (.-headers request)
                    headers (Headers.)
                    method (.-method request)
                    host (or (aget node-headers "host") (str hostname ":" port))
                    url (str "http://" host (.-url request))
                    body? (not (#{"GET" "HEAD"} method))
                    init #js {:method method :headers headers}]
                (when body? (aset init "body" request) (aset init "duplex" "half"))
                (.forEach (Object.entries node-headers)
                          (fn [entry]
                            (let [key (aget entry 0)
                                  value (aget entry 1)]
                              (cond (array? value) (.forEach value
                                                             #(.append headers key %))
                                    (some? value) (.set headers key value)))))
                ((^:async fn
                  []
                  (try (let [result (await (handler (Request. url init)))
                             headers (.-headers result)
                             get-set-cookie (aget headers "getSetCookie")
                             cookies (when get-set-cookie (.call get-set-cookie headers))]
                         (aset response "statusCode" (.-status result))
                         (.forEach headers
                                   (fn [value key]
                                     (when-not (= "set-cookie" (.toLowerCase key))
                                       (.setHeader response key value))))
                         (when (and cookies (pos? (.-length cookies)))
                           (.setHeader response "Set-Cookie" cookies))
                         (if-let [body (.-body result)]
                           (let [readable (.fromWeb (aget stream "Readable") body)]
                             (.on response
                                  "close"
                                  (fn []
                                    (.destroy readable)))
                             (.on readable
                                  "error"
                                  (fn [error]
                                    (.destroy response error)))
                             (.pipe readable response))
                           (.end response)))
                       (catch :default error
                         (.error console error)
                         (aset response "statusCode" 500)
                         (.end response "Internal Server Error"))))))))
        state #js {}
        close (fn [force?]
                (or (aget state "promise")
                    (let [promise (shutdown-node server force?)]
                      (aset state "promise" promise)
                      promise)))
        shutdown (fn [force?]
                   (let [promise (close force?)]
                     (when-not (.-aborted (.-signal controller)) (.abort controller))
                     promise))
        options #js {:host hostname :port port}]
    (.on
      server
      "upgrade"
      (fn [_request socket _head]
        (.end
          socket
          "HTTP/1.1 501 Not Implemented\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")))
    (.addEventListener (.-signal controller)
                       "abort"
                       (fn []
                         (close false))
                       #js {:once true})
    (register! server controller "node" shutdown)
    (when (some? reuse-port) (aset options "reusePort" (boolean reuse-port)))
    (.listen server
             options
             (fn []
               (when on-listen
                 (on-listen (proxy {:hostname hostname
                                    :port (or (.-port (.address server)) port)})))))
    server))

(defn serve!
  [handler hostname port on-listen reuse-port]
  (let [port (Number port)
        proxy (proxy/builder)
        deno (aget globalThis "Deno")
        bun (aget globalThis "Bun")
        process (aget globalThis "process")]
    (when-not handler
      (throw (Error. "serve requires :handler or :app with handler/fetch")))
    (cond deno (serve-deno deno handler hostname port on-listen reuse-port proxy)
          bun (serve-bun bun handler hostname port on-listen reuse-port proxy)
          (and process (aget process "versions") (aget (aget process "versions") "node"))
            (serve-node process handler hostname port on-listen reuse-port proxy)
          :else (throw (Error. "No supported server runtime found")))))

(defn shutdown-deno
  [server]
  (if-let [shutdown (when server (aget server "shutdown"))]
    (.call shutdown server)
    (throw (Error. "shutdown requires a Deno server returned by serve"))))

(defn shutdown-bun
  [server force]
  (if-let [stop (when server (aget server "stop"))]
    (.call stop server (boolean force))
    (throw (Error. "shutdown requires a Bun server returned by serve"))))

(defn shutdown-registered
  [entry force]
  (if-let [shutdown (when entry (aget entry "shutdown"))]
    (.call shutdown entry (boolean force))
    (throw (Error. "shutdown requires a server returned by serve"))))

(defn shutdown!
  [server force]
  (let [registry (aget globalThis "__fastTwitchShutdownRegistry")
        entry (when (and server registry) (.get registry server))]
    (cond entry (shutdown-registered entry force)
          (and server (aget server "shutdown")) (shutdown-deno server)
          (and server (aget server "stop")) (shutdown-bun server force)
          (and server (aget server "close")) (shutdown-node server force)
          :else (throw (Error. "shutdown requires a server returned by serve")))))
