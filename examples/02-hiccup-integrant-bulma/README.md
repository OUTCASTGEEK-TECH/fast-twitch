# 03 - Hiccup, Integrant, and Bulma

This example renders HTML from Hiccup data.

If you are brand new:

- Hiccup is just Clojure data that describes HTML.
- `[:h1 "Hello"]` means `<h1>Hello</h1>`.
- Integrant starts small pieces of the app from a config map.
- Bulma is a CSS framework loaded from a CDN so the page looks decent without writing CSS first.

## Run it

```sh
bb run-node
```

Or use the same compiled app with another runtime:

```sh
bb run-deno
bb run-bun
```

Then open:

```text
http://127.0.0.1:6464/
```

Try a path parameter:

```text
http://127.0.0.1:6464/hello/Ada
```

Look at `src/hiccupapp/server.cljs` from the bottom up:

1. `config` says which pieces exist.
2. `ig/init-key` methods explain how to build each piece.
3. Route handlers return response maps.
4. HTML response bodies are rendered from Hiccup vectors.

