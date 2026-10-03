# 05 - Celld

A small ClojureScript Worker using the patched framework from `../..`.
`routing/ft-handler` adapts Fast-Twitch routes and response maps to the runtime's
native Fetch `Request` and `Response`. The application lives in
`src/celld_example/worker.cljs`; `entry.mjs` only exports its compiled Fetch handler.

## Run

Requires Node 22.12+, Java 21+, Clojure CLI and Babashka. Also install [Celld](https://celld.dev/docs/) and put `celld` on your PATH.

From this directory:

```sh
npm ci
bb run-celld
```

Babashka invokes the ClojureScript compiler, then Vite writes `target/worker.mjs`.
`wrangler.jsonc` points Celld at that bundled module. The server listens on port
9876. If Celld is installed outside PATH, use:

```sh
CELLD_BIN="$HOME/.local/bin/celld" bb run-celld
```

## Try the routes

In another terminal:

```sh
curl -i http://127.0.0.1:9876/
curl -i http://127.0.0.1:9876/hello/Ada
curl -i --data 'hello from a Fetch request' http://127.0.0.1:9876/echo
curl -i http://127.0.0.1:9876/missing
```

The root response contains `X-Runtime: celld` and two separate `Set-Cookie`
headers. This exercises the native `ResponseInit` and header tuples from the
adapter patch. `/hello/:name` demonstrates route parameters; `/echo` reads the
original Fetch request body; an unknown route returns 404.
