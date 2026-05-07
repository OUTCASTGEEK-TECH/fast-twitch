# 04 - File Upload and Static Files

This example keeps the browser-facing pieces small:

- Integrant builds settings, pages, routes, and the final app.
- Hiccup vectors render HTML.
- Bulma styles the upload form.
- `wrap-multipart-params` reads file uploads into the request map.
- `wrap-file` serves files from the `static` directory at `/static/...`.

The upload is intentionally simple. It reports the uploaded file name, browser-provided content type, and byte size. It does not save the upload to disk.

## Run it

```sh
bb run-node
```

Or run the same compiled app with Deno or Bun:

```sh
bb run-deno
bb run-bun
```

Open:

```text
http://127.0.0.1:6464/
```

This route also shows the same upload form, so browsing to the POST target is not a dead end:

```text
http://127.0.0.1:6464/upload
```

The static file endpoint is:

```text
http://127.0.0.1:6464/static/readme.txt
```

You can also upload with curl:

```sh
curl -i -X POST \
  -F 'upload[note]=from curl' \
  -F 'upload[file]=@static/readme.txt;type=text/plain' \
  'http://127.0.0.1:6464/upload'
```
