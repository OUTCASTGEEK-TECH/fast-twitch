# 01 - Hello World

This is the smallest useful `fast-twitch` server.

It shows the same shape used by Ring in Clojure:

1. A handler receives a request map.
2. The handler returns a response map with `:status`, `:headers`, and `:body`.
3. `fast-twitch` adapts that map-based handler to the Fetch APIs used by Node, Deno, and Bun.

## Run it

```sh
bb run-node
```

The same compiled `server.cjs` also runs on Deno and Bun:

```sh
bb run-deno
bb run-bun
```

You can pick a port with `PORT`:

```sh
PORT=7777 bb run-node
```

Then request it:

```sh
curl -i 'http://127.0.0.1:6464/?name=newbie'
```

Watch the terminal too. The handler prints the request map there before it sends the response.

