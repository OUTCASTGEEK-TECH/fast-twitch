# Native transport examples

Run from this directory with JDK 21 or newer; the repository's `.envrc` selects Java 25 through SDKMAN when direnv is enabled. Each program creates its own local resources and closes them before exiting.

```sh
clojure -M:build http
node target/http.cjs

clojure -M:build websocket
bun target/websocket.cjs
deno run -A target/websocket.cjs

clojure -M:build sse
node target/sse.cjs
deno run -A target/sse.cjs

clojure -M:build streams
node target/streams.cjs

clojure -M:build ports
node target/ports.cjs

clojure -M:build tcp-node
node target/tcp-node.cjs
clojure -M:build tcp-deno
deno run -A target/tcp-deno.cjs
clojure -M:build tcp-bun
bun target/tcp-bun.cjs
```

Each example uses named functions from `fast-twitch.client.core`, `fast-twitch.server.core` and `fast-twitch.server.sse` with ordinary data and option maps. HTTP forwards native responses with visible status/header edits and selects a text codec. WebSocket uses a Ring listener through the existing routing and serving lifecycle. SSE consumes a Fetch stream and, where native EventSource exists, a named event. Streams configures cancellation and reader ownership. MessageChannel selects a JSON codec. TCP configures runtime, byte codec and stream bounds on `connect!`/`listen!`, then calls client `send!`/`receive!` on connection data from either endpoint; it needs no stream extraction. Omitting `:runtime` selects the current native runtime internally.

Node's native server WebSocket upgrade and Node/Bun native EventSource are unavailable. Unsupported capabilities are rejected. See the [public API](../../docs/API.md) for client/server functions and settings.
