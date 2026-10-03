# 06 - Cloudflare workerd

A small ClojureScript Worker using the patched framework from `../..`.
`routing/ft-handler` adapts Fast-Twitch routes and response maps to the runtime's
native Fetch `Request` and `Response`. The application lives in
`src/workerd_example/worker.cljs`; `entry.mjs` only exports its compiled Fetch handler.

## Run

Requires Node 22.12+, Java 21+, Clojure CLI and Babashka.

From this directory:

```sh
npm ci
bb run-workerd
```

Babashka invokes the ClojureScript compiler, then Vite writes `target/worker.mjs`.
`npm run serve` starts the installed workerd binary directly with `config.capnp`
on port 8787. After building, that command starts without rebuilding.

The config follows the [workerd service/socket configuration](https://github.com/cloudflare/workerd/tree/main/samples/helloworld), using an ES module Worker.

## Try the routes

In another terminal:

```sh
curl -i http://127.0.0.1:8787/
curl -i http://127.0.0.1:8787/hello/Ada
curl -i --data 'hello from a Fetch request' http://127.0.0.1:8787/echo
curl -i http://127.0.0.1:8787/missing
```

The root response contains `X-Runtime: cloudflare-workerd` and two separate `Set-Cookie`
headers. This exercises the native `ResponseInit` and header tuples from the
adapter patch. `/hello/:name` demonstrates route parameters; `/echo` reads the
original Fetch request body; an unknown route returns 404.
