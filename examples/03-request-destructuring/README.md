# 03 - Request Destructuring

This example is for learning how to pull values out of the `fast-twitch` request map.

The important ClojureScript idea is destructuring. Instead of writing many `get` calls, a function can say which map keys it wants right in its argument list or in a `let`.

## Run it

Requires Java 21+, Clojure CLI, Babashka, and Node, Bun or Deno.

```sh
bb run-node
```

The same app can run on Deno or Bun:

```sh
bb run-deno
bb run-bun
```

To build and serve the exported Fetch handler with Deno:

```sh
bb deno-serve
```

This task builds `build/server-serve.cjs` without CLI startup. The normal
`run-node`, `run-bun` and `run-deno` tasks build and run `server.cjs`.

## Try the endpoints

Open the app in a browser first:

```text
http://127.0.0.1:6464/
```

The page has Bulma-styled forms for every POST route:

- `GET /form` shows a form that posts to `POST /form`
- `GET /profile` shows a form that posts to `POST /profile`
- `GET /body-text` shows a form that posts to `POST /body-text`

You can also use curl.

Path params, query params, headers, URI, and method:

```sh
curl -i -H 'X-Learner: first-day' \
  'http://127.0.0.1:6464/hello/Ada?loud=true&topic=fast-twitch'
```

URL-encoded form params:

```sh
curl -i -X POST \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'color=blue&color=green&level=beginner' \
  'http://127.0.0.1:6464/form'
```

Nested form params:

```sh
curl -i -X POST \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'user[name]=Ada&user[role]=newbie&user[language]=ClojureScript' \
  'http://127.0.0.1:6464/profile'
```

Raw body text:

```sh
curl -i -X POST \
  -H 'Content-Type: text/plain' \
  --data 'plain request bodies are streams until you read them' \
  'http://127.0.0.1:6464/body-text'
```

Each handler logs the destructured data before returning a normal response map.
