# Table of contents
-  [`fast-twitch.macros`](#fast-twitch.macros)  - Compile-time helpers for runtime detection, environment lookup, and server startup code generation.
    -  [`current-runtime`](#fast-twitch.macros/current-runtime) - Expands to a keyword naming the active JavaScript runtime, or nil when unsupported.
    -  [`env-var`](#fast-twitch.macros/env-var) - Reads an environment variable from Deno or Node-compatible globals at runtime.
    -  [`serve`](#fast-twitch.macros/serve) - Expands to runtime-specific server startup code for Deno, Bun, or Node.
    -  [`serve-bun`](#fast-twitch.macros/serve-bun) - Builds the Bun server bootstrap form and normalizes the listen callback payload.
    -  [`serve-deno`](#fast-twitch.macros/serve-deno) - Builds the Deno server bootstrap form for the provided handler and options.
    -  [`serve-node`](#fast-twitch.macros/serve-node) - Builds the Node HTTP server bootstrap form, including request and response adaptation.
-  [`fast-twitch.middlewares.absolute-redirects`](#fast-twitch.middlewares.absolute-redirects)  - Rewrites redirect targets so clients receive fully qualified locations when needed.
    -  [`absolute-redirects-response`](#fast-twitch.middlewares.absolute-redirects/absolute-redirects-response) - Normalizes redirect responses so their Location header is absolute.
    -  [`redirect-statuses`](#fast-twitch.middlewares.absolute-redirects/redirect-statuses)
    -  [`wrap-absolute-redirects`](#fast-twitch.middlewares.absolute-redirects/wrap-absolute-redirects) - Wraps a handler so redirect responses carry absolute Location headers.
-  [`fast-twitch.middlewares.anti-forgery`](#fast-twitch.middlewares.anti-forgery)  - Adds request token validation and token persistence for unsafe form submissions.
    -  [`*anti-forgery-token*`](#fast-twitch.middlewares.anti-forgery/*anti-forgery-token*)
    -  [`default-error-response`](#fast-twitch.middlewares.anti-forgery/default-error-response)
    -  [`unsafe-methods`](#fast-twitch.middlewares.anti-forgery/unsafe-methods)
    -  [`wrap-anti-forgery`](#fast-twitch.middlewares.anti-forgery/wrap-anti-forgery) - Wraps a handler with anti-forgery token validation and session token storage.
-  [`fast-twitch.middlewares.common`](#fast-twitch.middlewares.common)  - Shared helpers for header handling, request conversion, and middleware composition.
    -  [`append-header`](#fast-twitch.middlewares.common/append-header) - Appends a header value while preserving any existing header entries.
    -  [`assoc-header`](#fast-twitch.middlewares.common/assoc-header) - Associates a header on a response map.
    -  [`fetch-response->ft`](#fast-twitch.middlewares.common/fetch-response->ft) - Converts a Fetch Response instance into a response map.
    -  [`ft->fetch-request`](#fast-twitch.middlewares.common/ft->fetch-request) - Converts a request map into a Fetch Request instance.
    -  [`has-header?`](#fast-twitch.middlewares.common/has-header?) - Returns true when the given header is present.
    -  [`header-key`](#fast-twitch.middlewares.common/header-key) - Normalizes a header name to a lowercase keyword.
    -  [`header-value`](#fast-twitch.middlewares.common/header-value) - Looks up a header value without caring about header name casing.
    -  [`headers->entries`](#fast-twitch.middlewares.common/headers->entries) - Converts a header map into name/value entry pairs for Fetch APIs.
    -  [`headers->map`](#fast-twitch.middlewares.common/headers->map) - Converts a Fetch Headers instance into a plain Clojure map.
    -  [`promise`](#fast-twitch.middlewares.common/promise) - Wraps x in a resolved JavaScript promise.
    -  [`promise?`](#fast-twitch.middlewares.common/promise?) - Returns true when x behaves like a JavaScript promise.
    -  [`remove-headers`](#fast-twitch.middlewares.common/remove-headers) - Removes all headers whose names match the supplied collection.
    -  [`request-url`](#fast-twitch.middlewares.common/request-url) - Builds a full request URL string from a request map.
    -  [`wrap-request`](#fast-twitch.middlewares.common/wrap-request) - Wraps a handler with a request transformation that may be asynchronous.
    -  [`wrap-request-response`](#fast-twitch.middlewares.common/wrap-request-response) - Wraps a handler with coordinated request and response transformations.
    -  [`wrap-response`](#fast-twitch.middlewares.common/wrap-response) - Wraps a handler with a response transformation that sees the original request.
-  [`fast-twitch.middlewares.content-length`](#fast-twitch.middlewares.content-length)  - Adds a Content-Length header when the response body size can be determined eagerly.
    -  [`content-length-response`](#fast-twitch.middlewares.content-length/content-length-response) - Adds Content-Length to a response when it is missing and calculable.
    -  [`wrap-content-length`](#fast-twitch.middlewares.content-length/wrap-content-length) - Wraps a handler so its responses gain a Content-Length header when possible.
-  [`fast-twitch.middlewares.content-type`](#fast-twitch.middlewares.content-type)  - Infers content types from filenames, bytes, and file metadata for responses and uploads.
    -  [`aifc-prefix`](#fast-twitch.middlewares.content-type/aifc-prefix)
    -  [`aiff-prefix`](#fast-twitch.middlewares.content-type/aiff-prefix)
    -  [`avi-prefix`](#fast-twitch.middlewares.content-type/avi-prefix)
    -  [`base-content-type`](#fast-twitch.middlewares.content-type/base-content-type) - Strips parameters from a content type and lowercases the main media type.
    -  [`bytes-at?`](#fast-twitch.middlewares.content-type/bytes-at?) - Returns true when the bytes at an offset match the given signature.
    -  [`bytes-start-with?`](#fast-twitch.middlewares.content-type/bytes-start-with?) - Returns true when the byte sequence starts with the given signature.
    -  [`compatible-content-type?`](#fast-twitch.middlewares.content-type/compatible-content-type?) - Returns true when two content types should be treated as equivalent.
    -  [`content-type`](#fast-twitch.middlewares.content-type/content-type)
    -  [`content-type-aliases`](#fast-twitch.middlewares.content-type/content-type-aliases)
    -  [`content-type-response`](#fast-twitch.middlewares.content-type/content-type-response) - Adds a Content-Type header to responses that do not already specify one.
    -  [`content-type-warning`](#fast-twitch.middlewares.content-type/content-type-warning) - Explains the first mismatch found between declared, expected, and sniffed types.
    -  [`ebml-prefix`](#fast-twitch.middlewares.content-type/ebml-prefix)
    -  [`eot-prefix`](#fast-twitch.middlewares.content-type/eot-prefix)
    -  [`expected-content-type`](#fast-twitch.middlewares.content-type/expected-content-type) - Looks up the extension-derived content type for a filename.
    -  [`file-bytes`](#fast-twitch.middlewares.content-type/file-bytes) - Reads the leading bytes used for file content sniffing.
    -  [`file-content-type-summary`](#fast-twitch.middlewares.content-type/file-content-type-summary) - Builds an upload summary with declared, expected, sniffed, and warning fields.
    -  [`filename-extension`](#fast-twitch.middlewares.content-type/filename-extension) - Returns the extension portion of a filename, including the leading dot.
    -  [`form-prefix`](#fast-twitch.middlewares.content-type/form-prefix)
    -  [`ftyp-prefix`](#fast-twitch.middlewares.content-type/ftyp-prefix)
    -  [`html-tag-prefixes`](#fast-twitch.middlewares.content-type/html-tag-prefixes)
    -  [`mp4-brand-prefix`](#fast-twitch.middlewares.content-type/mp4-brand-prefix)
    -  [`prefix-content-types`](#fast-twitch.middlewares.content-type/prefix-content-types)
    -  [`resource-header-size`](#fast-twitch.middlewares.content-type/resource-header-size)
    -  [`riff-prefix`](#fast-twitch.middlewares.content-type/riff-prefix)
    -  [`sniff-content-type`](#fast-twitch.middlewares.content-type/sniff-content-type) - Infers a content type from leading bytes using signature and text heuristics.
    -  [`starts-with`](#fast-twitch.middlewares.content-type/starts-with)
    -  [`svg-tag-prefix`](#fast-twitch.middlewares.content-type/svg-tag-prefix)
    -  [`tar-prefix`](#fast-twitch.middlewares.content-type/tar-prefix)
    -  [`wave-prefix`](#fast-twitch.middlewares.content-type/wave-prefix)
    -  [`webm-doctype`](#fast-twitch.middlewares.content-type/webm-doctype)
    -  [`webp-chunk-prefix`](#fast-twitch.middlewares.content-type/webp-chunk-prefix)
    -  [`webp-prefix`](#fast-twitch.middlewares.content-type/webp-prefix)
    -  [`wrap-content-type`](#fast-twitch.middlewares.content-type/wrap-content-type) - Wraps a handler so missing Content-Type headers are inferred automatically.
    -  [`xml-prefix`](#fast-twitch.middlewares.content-type/xml-prefix)
-  [`fast-twitch.middlewares.cookies`](#fast-twitch.middlewares.cookies)  - Parses incoming cookies and serializes outgoing cookie instructions.
    -  [`cookies-request`](#fast-twitch.middlewares.cookies/cookies-request) - Associates parsed cookies on the request map.
    -  [`cookies-response`](#fast-twitch.middlewares.cookies/cookies-response) - Serializes response cookies into Set-Cookie headers and removes :cookies.
    -  [`wrap-cookies`](#fast-twitch.middlewares.cookies/wrap-cookies) - Wraps a handler with cookie parsing on the way in and serialization on the way out.
-  [`fast-twitch.middlewares.default-charset`](#fast-twitch.middlewares.default-charset)  - Appends a charset parameter to text-based responses that do not already declare one.
    -  [`default-charset-response`](#fast-twitch.middlewares.default-charset/default-charset-response) - Adds a default charset to eligible responses.
    -  [`wrap-default-charset`](#fast-twitch.middlewares.default-charset/wrap-default-charset) - Wraps a handler so text responses receive a fallback charset.
-  [`fast-twitch.middlewares.defaults`](#fast-twitch.middlewares.defaults)  - Preconfigured middleware bundles for common API and site-oriented applications.
    -  [`api-defaults`](#fast-twitch.middlewares.defaults/api-defaults)
    -  [`site-defaults`](#fast-twitch.middlewares.defaults/site-defaults)
    -  [`wrap-defaults`](#fast-twitch.middlewares.defaults/wrap-defaults) - Applies the configured default middleware bundles to a handler.
-  [`fast-twitch.middlewares.file`](#fast-twitch.middlewares.file)  - Serves files from a local path with runtime-specific filesystem access and path safety checks.
    -  [`file-request`](#fast-twitch.middlewares.file/file-request) - Attempts to serve a file for the request and returns nil when nothing matches.
    -  [`wrap-file`](#fast-twitch.middlewares.file/wrap-file) - Wraps a handler with filesystem-backed static file serving.
-  [`fast-twitch.middlewares.file-info`](#fast-twitch.middlewares.file-info)  - Adds metadata-derived headers for file-backed responses and applies cache helpers.
    -  [`file-info-response`](#fast-twitch.middlewares.file-info/file-info-response) - Adds file-derived headers and cache helpers to a response.
    -  [`wrap-file-info`](#fast-twitch.middlewares.file-info/wrap-file-info) - Wraps a handler so file-like responses gain metadata and cache headers.
-  [`fast-twitch.middlewares.flash`](#fast-twitch.middlewares.flash)  - Moves flash data between the session and request or response maps.
    -  [`flash-request`](#fast-twitch.middlewares.flash/flash-request) - Loads flash data from the session and clears it for the next request.
    -  [`flash-response`](#fast-twitch.middlewares.flash/flash-response) - Stores outgoing flash data back into the session.
    -  [`wrap-flash`](#fast-twitch.middlewares.flash/wrap-flash) - Wraps a handler with flash loading and persistence.
-  [`fast-twitch.middlewares.head`](#fast-twitch.middlewares.head)  - Treats HEAD requests like GET requests while omitting the response body.
    -  [`head-request`](#fast-twitch.middlewares.head/head-request) - Transforms a HEAD request into a GET request for handler execution.
    -  [`head-response`](#fast-twitch.middlewares.head/head-response) - Clears the response body when the original request method was HEAD.
    -  [`wrap-head`](#fast-twitch.middlewares.head/wrap-head) - Wraps a handler with HEAD request and response adjustments.
-  [`fast-twitch.middlewares.keyword-params`](#fast-twitch.middlewares.keyword-params)  - Converts string parameter keys into keywords throughout parsed parameter maps.
    -  [`keyword-params-request`](#fast-twitch.middlewares.keyword-params/keyword-params-request) - Keywordizes parameter maps already associated with the request.
    -  [`wrap-keyword-params`](#fast-twitch.middlewares.keyword-params/wrap-keyword-params) - Wraps a handler so parsed parameters use keyword keys.
-  [`fast-twitch.middlewares.multipart-params`](#fast-twitch.middlewares.multipart-params)  - Parses multipart form bodies and exposes uploads in a request-friendly map shape.
    -  [`content-too-large-handler`](#fast-twitch.middlewares.multipart-params/content-too-large-handler) - Returns a standard 413 response in both sync and async handler forms.
    -  [`content-too-large-response`](#fast-twitch.middlewares.multipart-params/content-too-large-response)
    -  [`multipart-params-request`](#fast-twitch.middlewares.multipart-params/multipart-params-request) - Associates parsed multipart parameters onto the request.
    -  [`parse-multipart-params`](#fast-twitch.middlewares.multipart-params/parse-multipart-params) - Parses multipart parameters from the request body.
    -  [`wrap-multipart-params`](#fast-twitch.middlewares.multipart-params/wrap-multipart-params) - Wraps a handler so multipart form data is available on the request.
-  [`fast-twitch.middlewares.nested-params`](#fast-twitch.middlewares.nested-params)  - Turns bracketed parameter names into nested maps and vectors.
    -  [`nested-params-request`](#fast-twitch.middlewares.nested-params/nested-params-request) - Rewrites parsed parameter maps using nested structures.
    -  [`parse-nested-keys`](#fast-twitch.middlewares.nested-params/parse-nested-keys) - Splits a bracketed parameter name into its path segments.
    -  [`wrap-nested-params`](#fast-twitch.middlewares.nested-params/wrap-nested-params) - Wraps a handler so bracketed parameter names become nested data.
-  [`fast-twitch.middlewares.not-modified`](#fast-twitch.middlewares.not-modified)  - Short-circuits cacheable responses when validators show the resource is unchanged.
    -  [`not-modified-response`](#fast-twitch.middlewares.not-modified/not-modified-response) - Replaces a cache hit response with a 304 response for GET and HEAD requests.
    -  [`wrap-not-modified`](#fast-twitch.middlewares.not-modified/wrap-not-modified) - Wraps a handler so conditional requests can return 304 responses.
-  [`fast-twitch.middlewares.params`](#fast-twitch.middlewares.params)  - Parses query strings and URL-encoded form bodies into request parameter maps.
    -  [`assoc-form-params`](#fast-twitch.middlewares.params/assoc-form-params) - Associates parsed URL-encoded form parameters onto the request.
    -  [`assoc-query-params`](#fast-twitch.middlewares.params/assoc-query-params) - Associates parsed query parameters onto the request.
    -  [`params-request`](#fast-twitch.middlewares.params/params-request) - Parses query and URL-encoded form parameters for a request.
    -  [`wrap-params`](#fast-twitch.middlewares.params/wrap-params) - Wraps a handler so query and form parameters are available on the request.
-  [`fast-twitch.middlewares.proxy-headers`](#fast-twitch.middlewares.proxy-headers)  - Adapts request connection details from common forwarding headers.
    -  [`proxy-headers-request`](#fast-twitch.middlewares.proxy-headers/proxy-headers-request) - Associates forwarded connection details onto the request map.
    -  [`wrap-forwarded-headers`](#fast-twitch.middlewares.proxy-headers/wrap-forwarded-headers) - Wraps a handler so forwarding headers update request connection fields.
-  [`fast-twitch.middlewares.resource`](#fast-twitch.middlewares.resource)  - Provides resource-style static asset serving through the file middleware.
    -  [`resource-request`](#fast-twitch.middlewares.resource/resource-request) - Attempts to build a static resource response for the current request.
    -  [`wrap-resource`](#fast-twitch.middlewares.resource/wrap-resource) - Wraps a handler with static resource serving for a given root path.
-  [`fast-twitch.middlewares.session`](#fast-twitch.middlewares.session)  - Stores per-client session data in an atom-backed store and persists the key in a cookie.
    -  [`default-store`](#fast-twitch.middlewares.session/default-store)
    -  [`delete-session`](#fast-twitch.middlewares.session/delete-session) - Removes session data for the given key and returns nil for convenience.
    -  [`memory-store`](#fast-twitch.middlewares.session/memory-store) - Creates a fresh in-memory session store.
    -  [`read-session`](#fast-twitch.middlewares.session/read-session) - Reads session data for the given key from the store.
    -  [`session-request`](#fast-twitch.middlewares.session/session-request) - Associates session metadata and session data onto the request map.
    -  [`session-response`](#fast-twitch.middlewares.session/session-response) - Persists response session data and emits the corresponding session cookie.
    -  [`wrap-session`](#fast-twitch.middlewares.session/wrap-session) - Wraps a handler with session loading and persistence.
    -  [`write-session`](#fast-twitch.middlewares.session/write-session) - Writes session data and returns the existing or generated session key.
-  [`fast-twitch.middlewares.ssl`](#fast-twitch.middlewares.ssl)  - Redirects insecure traffic and adds strict transport security headers when configured.
    -  [`hsts-response`](#fast-twitch.middlewares.ssl/hsts-response) - Adds a Strict-Transport-Security header to HTTPS responses.
    -  [`wrap-hsts`](#fast-twitch.middlewares.ssl/wrap-hsts) - Wraps a handler so HTTPS responses include strict transport security metadata.
    -  [`wrap-ssl`](#fast-twitch.middlewares.ssl/wrap-ssl) - Composes HTTPS redirect and strict transport security behavior from options.
    -  [`wrap-ssl-redirect`](#fast-twitch.middlewares.ssl/wrap-ssl-redirect) - Wraps a handler so non-HTTPS requests receive a permanent redirect.
-  [`fast-twitch.middlewares.x-headers`](#fast-twitch.middlewares.x-headers)  - Adds common hardening headers to outgoing responses.
    -  [`wrap-x-headers`](#fast-twitch.middlewares.x-headers/wrap-x-headers) - Wraps a handler so configured X-* headers are applied to responses.
    -  [`x-headers-response`](#fast-twitch.middlewares.x-headers/x-headers-response) - Adds configured X-* headers to the response.
-  [`fast-twitch.routing`](#fast-twitch.routing)  - Routing and handler adaptation helpers for translating between Fetch APIs and request maps.
    -  [`build-request-map`](#fast-twitch.routing/build-request-map) - Builds the request map consumed by application handlers.
    -  [`ft-handler`](#fast-twitch.routing/ft-handler) - Wraps an application handler as a Fetch-compatible function.
    -  [`header`](#fast-twitch.routing/header) - Associates a header value on a response map.
    -  [`not-found`](#fast-twitch.routing/not-found) - Builds a 404 response map with the supplied body.
    -  [`proxy`](#fast-twitch.routing/proxy)
    -  [`response`](#fast-twitch.routing/response) - Builds a 200 response map with the supplied body.
    -  [`response?`](#fast-twitch.routing/response?) - Returns true when a value matches the expected response map shape.
    -  [`routes`](#fast-twitch.routing/routes) - Builds a dispatching handler from route definitions and a fallback handler.
    -  [`run-adapter`](#fast-twitch.routing/run-adapter) - Starts the runtime adapter for an application or handler.
    -  [`status`](#fast-twitch.routing/status) - Creates a bare response for a status code or updates an existing response map.
    -  [`url-pattern`](#fast-twitch.routing/url-pattern) - Builds a URLPattern that matches the given pathname.
-  [`fast-twitch.util.anti-forgery`](#fast-twitch.util.anti-forgery)  - View helpers for rendering anti-forgery form fields.
    -  [`anti-forgery-field`](#fast-twitch.util.anti-forgery/anti-forgery-field) - Returns a hidden form input populated with the current anti-forgery token.

-----
# <a name="fast-twitch.macros">fast-twitch.macros</a>


Compile-time helpers for runtime detection, environment lookup, and server startup code generation.




## <a name="fast-twitch.macros/current-runtime">`current-runtime`</a>
``` clojure
(current-runtime)
```
Macro.

Expands to a keyword naming the active JavaScript runtime, or nil when unsupported.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L15-L30">Source</a></sub></p>

## <a name="fast-twitch.macros/env-var">`env-var`</a>
``` clojure
(env-var v)
```
Macro.

Reads an environment variable from Deno or Node-compatible globals at runtime.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L4-L13">Source</a></sub></p>

## <a name="fast-twitch.macros/serve">`serve`</a>
``` clojure
(serve & {:keys [app handler host hostname port on-listen reuse-port]})
```
Macro.

Expands to runtime-specific server startup code for Deno, Bun, or Node.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L137-L193">Source</a></sub></p>

## <a name="fast-twitch.macros/serve-bun">`serve-bun`</a>
``` clojure
(serve-bun bun handler hostname port on-listen reuse-port proxy)
```
Function.

Builds the Bun server bootstrap form and normalizes the listen callback payload.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L46-L60">Source</a></sub></p>

## <a name="fast-twitch.macros/serve-deno">`serve-deno`</a>
``` clojure
(serve-deno deno handler hostname port on-listen reuse-port _proxy)
```
Function.

Builds the Deno server bootstrap form for the provided handler and options.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L32-L44">Source</a></sub></p>

## <a name="fast-twitch.macros/serve-node">`serve-node`</a>
``` clojure
(serve-node process handler hostname port on-listen reuse-port proxy)
```
Function.

Builds the Node HTTP server bootstrap form, including request and response adaptation.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/macros.cljc#L62-L135">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.absolute-redirects">fast-twitch.middlewares.absolute-redirects</a>


Rewrites redirect targets so clients receive fully qualified locations when needed.




## <a name="fast-twitch.middlewares.absolute-redirects/absolute-redirects-response">`absolute-redirects-response`</a>
``` clojure
(absolute-redirects-response response request)
```
Function.

Normalizes redirect responses so their Location header is absolute.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/absolute_redirects.cljs#L33-L40">Source</a></sub></p>

## <a name="fast-twitch.middlewares.absolute-redirects/redirect-statuses">`redirect-statuses`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/absolute_redirects.cljs#L8-L8">Source</a></sub></p>

## <a name="fast-twitch.middlewares.absolute-redirects/wrap-absolute-redirects">`wrap-absolute-redirects`</a>
``` clojure
(wrap-absolute-redirects handler)
```
Function.

Wraps a handler so redirect responses carry absolute Location headers.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/absolute_redirects.cljs#L42-L45">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.anti-forgery">fast-twitch.middlewares.anti-forgery</a>


Adds request token validation and token persistence for unsafe form submissions.




## <a name="fast-twitch.middlewares.anti-forgery/*anti-forgery-token*">`*anti-forgery-token*`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/anti_forgery.cljs#L5-L5">Source</a></sub></p>

## <a name="fast-twitch.middlewares.anti-forgery/default-error-response">`default-error-response`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/anti_forgery.cljs#L9-L12">Source</a></sub></p>

## <a name="fast-twitch.middlewares.anti-forgery/unsafe-methods">`unsafe-methods`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/anti_forgery.cljs#L7-L7">Source</a></sub></p>

## <a name="fast-twitch.middlewares.anti-forgery/wrap-anti-forgery">`wrap-anti-forgery`</a>
``` clojure
(wrap-anti-forgery handler)
(wrap-anti-forgery handler options)
```
Function.

Wraps a handler with anti-forgery token validation and session token storage.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/anti_forgery.cljs#L74-L100">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.common">fast-twitch.middlewares.common</a>


Shared helpers for header handling, request conversion, and middleware composition.




## <a name="fast-twitch.middlewares.common/append-header">`append-header`</a>
``` clojure
(append-header headers k v)
```
Function.

Appends a header value while preserving any existing header entries.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L41-L50">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/assoc-header">`assoc-header`</a>
``` clojure
(assoc-header response k v)
```
Function.

Associates a header on a response map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L36-L39">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/fetch-response->ft">`fetch-response->ft`</a>
``` clojure
(fetch-response->ft response)
```
Function.

Converts a Fetch Response instance into a response map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L98-L103">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/ft->fetch-request">`ft->fetch-request`</a>
``` clojure
(ft->fetch-request request)
```
Function.

Converts a request map into a Fetch Request instance.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L86-L96">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/has-header?">`has-header?`</a>
``` clojure
(has-header? headers k)
```
Function.

Returns true when the given header is present.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L31-L34">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/header-key">`header-key`</a>
``` clojure
(header-key k)
```
Function.

Normalizes a header name to a lowercase keyword.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L16-L19">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/header-value">`header-value`</a>
``` clojure
(header-value headers k)
```
Function.

Looks up a header value without caring about header name casing.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L21-L29">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/headers->entries">`headers->entries`</a>
``` clojure
(headers->entries headers)
```
Function.

Converts a header map into name/value entry pairs for Fetch APIs.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L61-L64">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/headers->map">`headers->map`</a>
``` clojure
(headers->map headers)
```
Function.

Converts a Fetch Headers instance into a plain Clojure map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L66-L72">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/promise">`promise`</a>
``` clojure
(promise x)
```
Function.

Wraps x in a resolved JavaScript promise.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L11-L14">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/promise?">`promise?`</a>
``` clojure
(promise? x)
```
Function.

Returns true when x behaves like a JavaScript promise.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L6-L9">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/remove-headers">`remove-headers`</a>
``` clojure
(remove-headers headers names)
```
Function.

Removes all headers whose names match the supplied collection.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L52-L59">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/request-url">`request-url`</a>
``` clojure
(request-url request)
```
Function.

Builds a full request URL string from a request map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L74-L84">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/wrap-request">`wrap-request`</a>
``` clojure
(wrap-request handler request-fn)
```
Function.

Wraps a handler with a request transformation that may be asynchronous.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L105-L118">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/wrap-request-response">`wrap-request-response`</a>
``` clojure
(wrap-request-response handler request-fn response-fn)
```
Function.

Wraps a handler with coordinated request and response transformations.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L134-L154">Source</a></sub></p>

## <a name="fast-twitch.middlewares.common/wrap-response">`wrap-response`</a>
``` clojure
(wrap-response handler response-fn)
```
Function.

Wraps a handler with a response transformation that sees the original request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/common.cljs#L120-L132">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.content-length">fast-twitch.middlewares.content-length</a>


Adds a Content-Length header when the response body size can be determined eagerly.




## <a name="fast-twitch.middlewares.content-length/content-length-response">`content-length-response`</a>
``` clojure
(content-length-response response _request)
```
Function.

Adds Content-Length to a response when it is missing and calculable.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_length.cljs#L15-L23">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-length/wrap-content-length">`wrap-content-length`</a>
``` clojure
(wrap-content-length handler)
```
Function.

Wraps a handler so its responses gain a Content-Length header when possible.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_length.cljs#L25-L28">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.content-type">fast-twitch.middlewares.content-type</a>


Infers content types from filenames, bytes, and file metadata for responses and uploads.




## <a name="fast-twitch.middlewares.content-type/aifc-prefix">`aifc-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L151-L152">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/aiff-prefix">`aiff-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L148-L149">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/avi-prefix">`avi-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L142-L143">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/base-content-type">`base-content-type`</a>
``` clojure
(base-content-type s)
```
Function.

Strips parameters from a content type and lowercases the main media type.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L172-L180">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/bytes-at?">`bytes-at?`</a>
``` clojure
(bytes-at? bytes offset signature)
```
Function.

Returns true when the bytes at an offset match the given signature.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L224-L230">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/bytes-start-with?">`bytes-start-with?`</a>
``` clojure
(bytes-start-with? bytes signature)
```
Function.

Returns true when the byte sequence starts with the given signature.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L232-L235">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/compatible-content-type?">`compatible-content-type?`</a>
``` clojure
(compatible-content-type? expected sniffed)
```
Function.

Returns true when two content types should be treated as equivalent.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L407-L417">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/content-type">`content-type`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L9-L11">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/content-type-aliases">`content-type-aliases`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L396-L405">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/content-type-response">`content-type-response`</a>
``` clojure
(content-type-response response request)
(content-type-response response request options)
```
Function.

Adds a Content-Type header to responses that do not already specify one.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L463-L474">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/content-type-warning">`content-type-warning`</a>
``` clojure
(content-type-warning declared expected sniffed)
```
Function.

Explains the first mismatch found between declared, expected, and sniffed types.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L419-L436">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/ebml-prefix">`ebml-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L160-L161">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/eot-prefix">`eot-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L169-L170">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/expected-content-type">`expected-content-type`</a>
``` clojure
(expected-content-type filename)
```
Function.

Looks up the extension-derived content type for a filename.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L210-L214">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/file-bytes">`file-bytes`</a>
``` clojure
(file-bytes file)
```
Function.

Reads the leading bytes used for file content sniffing.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L216-L222">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/file-content-type-summary">`file-content-type-summary`</a>
``` clojure
(file-content-type-summary file)
```
Function.

Builds an upload summary with declared, expected, sniffed, and warning fields.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L438-L461">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/filename-extension">`filename-extension`</a>
``` clojure
(filename-extension filename)
```
Function.

Returns the extension portion of a filename, including the leading dot.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L182-L186">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/form-prefix">`form-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L145-L146">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/ftyp-prefix">`ftyp-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L154-L155">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/html-tag-prefixes">`html-tag-prefixes`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L105-L122">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/mp4-brand-prefix">`mp4-brand-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L157-L158">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/prefix-content-types">`prefix-content-types`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L74-L103">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/resource-header-size">`resource-header-size`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L19-L19">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/riff-prefix">`riff-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L130-L131">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/sniff-content-type">`sniff-content-type`</a>
``` clojure
(sniff-content-type bytes)
```
Function.

Infers a content type from leading bytes using signature and text heuristics.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L381-L394">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/starts-with">`starts-with`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L13-L15">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/svg-tag-prefix">`svg-tag-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L124-L125">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/tar-prefix">`tar-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L166-L167">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/wave-prefix">`wave-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L139-L140">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/webm-doctype">`webm-doctype`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L163-L164">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/webp-chunk-prefix">`webp-chunk-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L136-L137">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/webp-prefix">`webp-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L133-L134">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/wrap-content-type">`wrap-content-type`</a>
``` clojure
(wrap-content-type handler)
(wrap-content-type handler options)
```
Function.

Wraps a handler so missing Content-Type headers are inferred automatically.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L476-L481">Source</a></sub></p>

## <a name="fast-twitch.middlewares.content-type/xml-prefix">`xml-prefix`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/content_type.cljs#L127-L128">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.cookies">fast-twitch.middlewares.cookies</a>


Parses incoming cookies and serializes outgoing cookie instructions.




## <a name="fast-twitch.middlewares.cookies/cookies-request">`cookies-request`</a>
``` clojure
(cookies-request request)
(cookies-request request _options)
```
Function.

Associates parsed cookies on the request map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/cookies.cljs#L36-L41">Source</a></sub></p>

## <a name="fast-twitch.middlewares.cookies/cookies-response">`cookies-response`</a>
``` clojure
(cookies-response response)
(cookies-response response _options)
```
Function.

Serializes response cookies into Set-Cookie headers and removes :cookies.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/cookies.cljs#L75-L88">Source</a></sub></p>

## <a name="fast-twitch.middlewares.cookies/wrap-cookies">`wrap-cookies`</a>
``` clojure
(wrap-cookies handler)
(wrap-cookies handler options)
```
Function.

Wraps a handler with cookie parsing on the way in and serialization on the way out.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/cookies.cljs#L90-L99">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.default-charset">fast-twitch.middlewares.default-charset</a>


Appends a charset parameter to text-based responses that do not already declare one.




## <a name="fast-twitch.middlewares.default-charset/default-charset-response">`default-charset-response`</a>
``` clojure
(default-charset-response response request)
(default-charset-response response _request charset)
```
Function.

Adds a default charset to eligible responses.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/default_charset.cljs#L14-L22">Source</a></sub></p>

## <a name="fast-twitch.middlewares.default-charset/wrap-default-charset">`wrap-default-charset`</a>
``` clojure
(wrap-default-charset handler)
(wrap-default-charset handler charset)
```
Function.

Wraps a handler so text responses receive a fallback charset.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/default_charset.cljs#L24-L29">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.defaults">fast-twitch.middlewares.defaults</a>


Preconfigured middleware bundles for common API and site-oriented applications.




## <a name="fast-twitch.middlewares.defaults/api-defaults">`api-defaults`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/defaults.cljs#L22-L36">Source</a></sub></p>

## <a name="fast-twitch.middlewares.defaults/site-defaults">`site-defaults`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/defaults.cljs#L38-L48">Source</a></sub></p>

## <a name="fast-twitch.middlewares.defaults/wrap-defaults">`wrap-defaults`</a>
``` clojure
(wrap-defaults handler options)
```
Function.

Applies the configured default middleware bundles to a handler.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/defaults.cljs#L122-L142">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.file">fast-twitch.middlewares.file</a>


Serves files from a local path with runtime-specific filesystem access and path safety checks.




## <a name="fast-twitch.middlewares.file/file-request">`file-request`</a>
``` clojure
(file-request request root-path)
(file-request request root-path options)
```
Function.

Attempts to serve a file for the request and returns nil when nothing matches.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/file.cljs#L225-L239">Source</a></sub></p>

## <a name="fast-twitch.middlewares.file/wrap-file">`wrap-file`</a>
``` clojure
(wrap-file handler root-path)
(wrap-file handler root-path options)
```
Function.

Wraps a handler with filesystem-backed static file serving.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/file.cljs#L241-L264">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.file-info">fast-twitch.middlewares.file-info</a>


Adds metadata-derived headers for file-backed responses and applies cache helpers.




## <a name="fast-twitch.middlewares.file-info/file-info-response">`file-info-response`</a>
``` clojure
(file-info-response response request)
(file-info-response response request options)
```
Function.

Adds file-derived headers and cache helpers to a response.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/file_info.cljs#L25-L43">Source</a></sub></p>

## <a name="fast-twitch.middlewares.file-info/wrap-file-info">`wrap-file-info`</a>
``` clojure
(wrap-file-info handler)
(wrap-file-info handler options)
```
Function.

Wraps a handler so file-like responses gain metadata and cache headers.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/file_info.cljs#L45-L50">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.flash">fast-twitch.middlewares.flash</a>


Moves flash data between the session and request or response maps.




## <a name="fast-twitch.middlewares.flash/flash-request">`flash-request`</a>
``` clojure
(flash-request request)
```
Function.

Loads flash data from the session and clears it for the next request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/flash.cljs#L5-L11">Source</a></sub></p>

## <a name="fast-twitch.middlewares.flash/flash-response">`flash-response`</a>
``` clojure
(flash-response response request)
```
Function.

Stores outgoing flash data back into the session.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/flash.cljs#L13-L27">Source</a></sub></p>

## <a name="fast-twitch.middlewares.flash/wrap-flash">`wrap-flash`</a>
``` clojure
(wrap-flash handler)
```
Function.

Wraps a handler with flash loading and persistence.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/flash.cljs#L29-L32">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.head">fast-twitch.middlewares.head</a>


Treats HEAD requests like GET requests while omitting the response body.




## <a name="fast-twitch.middlewares.head/head-request">`head-request`</a>
``` clojure
(head-request request)
```
Function.

Transforms a HEAD request into a GET request for handler execution.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/head.cljs#L5-L10">Source</a></sub></p>

## <a name="fast-twitch.middlewares.head/head-response">`head-response`</a>
``` clojure
(head-response response request)
```
Function.

Clears the response body when the original request method was HEAD.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/head.cljs#L12-L17">Source</a></sub></p>

## <a name="fast-twitch.middlewares.head/wrap-head">`wrap-head`</a>
``` clojure
(wrap-head handler)
```
Function.

Wraps a handler with HEAD request and response adjustments.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/head.cljs#L19-L22">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.keyword-params">fast-twitch.middlewares.keyword-params</a>


Converts string parameter keys into keywords throughout parsed parameter maps.




## <a name="fast-twitch.middlewares.keyword-params/keyword-params-request">`keyword-params-request`</a>
``` clojure
(keyword-params-request request)
(keyword-params-request request _options)
```
Function.

Keywordizes parameter maps already associated with the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/keyword_params.cljs#L29-L45">Source</a></sub></p>

## <a name="fast-twitch.middlewares.keyword-params/wrap-keyword-params">`wrap-keyword-params`</a>
``` clojure
(wrap-keyword-params handler)
(wrap-keyword-params handler options)
```
Function.

Wraps a handler so parsed parameters use keyword keys.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/keyword_params.cljs#L47-L52">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.multipart-params">fast-twitch.middlewares.multipart-params</a>


Parses multipart form bodies and exposes uploads in a request-friendly map shape.




## <a name="fast-twitch.middlewares.multipart-params/content-too-large-handler">`content-too-large-handler`</a>
``` clojure
(content-too-large-handler _request)
(content-too-large-handler _request respond _raise)
```
Function.

Returns a standard 413 response in both sync and async handler forms.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/multipart_params.cljs#L13-L18">Source</a></sub></p>

## <a name="fast-twitch.middlewares.multipart-params/content-too-large-response">`content-too-large-response`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/multipart_params.cljs#L8-L11">Source</a></sub></p>

## <a name="fast-twitch.middlewares.multipart-params/multipart-params-request">`multipart-params-request`</a>
``` clojure
(multipart-params-request request)
(multipart-params-request request options)
```
Function.

Associates parsed multipart parameters onto the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/multipart_params.cljs#L71-L80">Source</a></sub></p>

## <a name="fast-twitch.middlewares.multipart-params/parse-multipart-params">`parse-multipart-params`</a>
``` clojure
(parse-multipart-params request)
(parse-multipart-params request _options)
```
Function.

Parses multipart parameters from the request body.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/multipart_params.cljs#L60-L69">Source</a></sub></p>

## <a name="fast-twitch.middlewares.multipart-params/wrap-multipart-params">`wrap-multipart-params`</a>
``` clojure
(wrap-multipart-params handler)
(wrap-multipart-params handler options)
```
Function.

Wraps a handler so multipart form data is available on the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/multipart_params.cljs#L82-L87">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.nested-params">fast-twitch.middlewares.nested-params</a>


Turns bracketed parameter names into nested maps and vectors.




## <a name="fast-twitch.middlewares.nested-params/nested-params-request">`nested-params-request`</a>
``` clojure
(nested-params-request request)
(nested-params-request request options)
```
Function.

Rewrites parsed parameter maps using nested structures.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/nested_params.cljs#L48-L65">Source</a></sub></p>

## <a name="fast-twitch.middlewares.nested-params/parse-nested-keys">`parse-nested-keys`</a>
``` clojure
(parse-nested-keys param-name)
```
Function.

Splits a bracketed parameter name into its path segments.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/nested_params.cljs#L5-L17">Source</a></sub></p>

## <a name="fast-twitch.middlewares.nested-params/wrap-nested-params">`wrap-nested-params`</a>
``` clojure
(wrap-nested-params handler)
(wrap-nested-params handler options)
```
Function.

Wraps a handler so bracketed parameter names become nested data.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/nested_params.cljs#L67-L72">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.not-modified">fast-twitch.middlewares.not-modified</a>


Short-circuits cacheable responses when validators show the resource is unchanged.




## <a name="fast-twitch.middlewares.not-modified/not-modified-response">`not-modified-response`</a>
``` clojure
(not-modified-response response request)
```
Function.

Replaces a cache hit response with a 304 response for GET and HEAD requests.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/not_modified.cljs#L42-L51">Source</a></sub></p>

## <a name="fast-twitch.middlewares.not-modified/wrap-not-modified">`wrap-not-modified`</a>
``` clojure
(wrap-not-modified handler)
```
Function.

Wraps a handler so conditional requests can return 304 responses.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/not_modified.cljs#L53-L56">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.params">fast-twitch.middlewares.params</a>


Parses query strings and URL-encoded form bodies into request parameter maps.




## <a name="fast-twitch.middlewares.params/assoc-form-params">`assoc-form-params`</a>
``` clojure
(assoc-form-params request)
(assoc-form-params request _encoding)
```
Function.

Associates parsed URL-encoded form parameters onto the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/params.cljs#L48-L63">Source</a></sub></p>

## <a name="fast-twitch.middlewares.params/assoc-query-params">`assoc-query-params`</a>
``` clojure
(assoc-query-params request)
(assoc-query-params request _encoding)
```
Function.

Associates parsed query parameters onto the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/params.cljs#L38-L46">Source</a></sub></p>

## <a name="fast-twitch.middlewares.params/params-request">`params-request`</a>
``` clojure
(params-request request)
(params-request request options)
```
Function.

Parses query and URL-encoded form parameters for a request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/params.cljs#L65-L76">Source</a></sub></p>

## <a name="fast-twitch.middlewares.params/wrap-params">`wrap-params`</a>
``` clojure
(wrap-params handler)
(wrap-params handler options)
```
Function.

Wraps a handler so query and form parameters are available on the request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/params.cljs#L78-L83">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.proxy-headers">fast-twitch.middlewares.proxy-headers</a>


Adapts request connection details from common forwarding headers.




## <a name="fast-twitch.middlewares.proxy-headers/proxy-headers-request">`proxy-headers-request`</a>
``` clojure
(proxy-headers-request request)
```
Function.

Associates forwarded connection details onto the request map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/proxy_headers.cljs#L29-L40">Source</a></sub></p>

## <a name="fast-twitch.middlewares.proxy-headers/wrap-forwarded-headers">`wrap-forwarded-headers`</a>
``` clojure
(wrap-forwarded-headers handler)
```
Function.

Wraps a handler so forwarding headers update request connection fields.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/proxy_headers.cljs#L42-L45">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.resource">fast-twitch.middlewares.resource</a>


Provides resource-style static asset serving through the file middleware.




## <a name="fast-twitch.middlewares.resource/resource-request">`resource-request`</a>
``` clojure
(resource-request request root-path)
(resource-request request root-path options)
```
Function.

Attempts to build a static resource response for the current request.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/resource.cljs#L6-L11">Source</a></sub></p>

## <a name="fast-twitch.middlewares.resource/wrap-resource">`wrap-resource`</a>
``` clojure
(wrap-resource handler root-path)
(wrap-resource handler root-path options)
```
Function.

Wraps a handler with static resource serving for a given root path.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/resource.cljs#L13-L18">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.session">fast-twitch.middlewares.session</a>


Stores per-client session data in an atom-backed store and persists the key in a cookie.




## <a name="fast-twitch.middlewares.session/default-store">`default-store`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L7-L7">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/delete-session">`delete-session`</a>
``` clojure
(delete-session store key)
```
Function.

Removes session data for the given key and returns nil for convenience.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L26-L31">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/memory-store">`memory-store`</a>
``` clojure
(memory-store)
```
Function.

Creates a fresh in-memory session store.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L9-L12">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/read-session">`read-session`</a>
``` clojure
(read-session store key)
```
Function.

Reads session data for the given key from the store.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L14-L17">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/session-request">`session-request`</a>
``` clojure
(session-request request)
(session-request request options)
```
Function.

Associates session metadata and session data onto the request map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L33-L46">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/session-response">`session-response`</a>
``` clojure
(session-response response request)
(session-response response request options)
```
Function.

Persists response session data and emits the corresponding session cookie.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L48-L71">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/wrap-session">`wrap-session`</a>
``` clojure
(wrap-session handler)
(wrap-session handler options)
```
Function.

Wraps a handler with session loading and persistence.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L73-L83">Source</a></sub></p>

## <a name="fast-twitch.middlewares.session/write-session">`write-session`</a>
``` clojure
(write-session store key data)
```
Function.

Writes session data and returns the existing or generated session key.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/session.cljs#L19-L24">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.ssl">fast-twitch.middlewares.ssl</a>


Redirects insecure traffic and adds strict transport security headers when configured.




## <a name="fast-twitch.middlewares.ssl/hsts-response">`hsts-response`</a>
``` clojure
(hsts-response response request)
(hsts-response response request options)
```
Function.

Adds a Strict-Transport-Security header to HTTPS responses.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/ssl.cljs#L34-L47">Source</a></sub></p>

## <a name="fast-twitch.middlewares.ssl/wrap-hsts">`wrap-hsts`</a>
``` clojure
(wrap-hsts handler)
(wrap-hsts handler options)
```
Function.

Wraps a handler so HTTPS responses include strict transport security metadata.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/ssl.cljs#L49-L54">Source</a></sub></p>

## <a name="fast-twitch.middlewares.ssl/wrap-ssl">`wrap-ssl`</a>
``` clojure
(wrap-ssl handler)
(wrap-ssl handler options)
```
Function.

Composes HTTPS redirect and strict transport security behavior from options.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/ssl.cljs#L56-L66">Source</a></sub></p>

## <a name="fast-twitch.middlewares.ssl/wrap-ssl-redirect">`wrap-ssl-redirect`</a>
``` clojure
(wrap-ssl-redirect handler)
(wrap-ssl-redirect handler _options)
```
Function.

Wraps a handler so non-HTTPS requests receive a permanent redirect.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/ssl.cljs#L15-L32">Source</a></sub></p>

-----
# <a name="fast-twitch.middlewares.x-headers">fast-twitch.middlewares.x-headers</a>


Adds common hardening headers to outgoing responses.




## <a name="fast-twitch.middlewares.x-headers/wrap-x-headers">`wrap-x-headers`</a>
``` clojure
(wrap-x-headers handler)
(wrap-x-headers handler options)
```
Function.

Wraps a handler so configured X-* headers are applied to responses.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/x_headers.cljs#L31-L36">Source</a></sub></p>

## <a name="fast-twitch.middlewares.x-headers/x-headers-response">`x-headers-response`</a>
``` clojure
(x-headers-response response request)
(x-headers-response response _request options)
```
Function.

Adds configured X-* headers to the response.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/middlewares/x_headers.cljs#L13-L29">Source</a></sub></p>

-----
# <a name="fast-twitch.routing">fast-twitch.routing</a>


Routing and handler adaptation helpers for translating between Fetch APIs and request maps.




## <a name="fast-twitch.routing/build-request-map">`build-request-map`</a>
``` clojure
(build-request-map request options)
(build-request-map request params options)
```
Function.

Builds the request map consumed by application handlers.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L101-L129">Source</a></sub></p>

## <a name="fast-twitch.routing/ft-handler">`ft-handler`</a>
``` clojure
(ft-handler handler options)
```
Function.

Wraps an application handler as a Fetch-compatible function.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L168-L191">Source</a></sub></p>

## <a name="fast-twitch.routing/header">`header`</a>
``` clojure
(header response name value)
```
Function.

Associates a header value on a response map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L28-L31">Source</a></sub></p>

## <a name="fast-twitch.routing/not-found">`not-found`</a>
``` clojure
(not-found body)
```
Function.

Builds a 404 response map with the supplied body.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L33-L38">Source</a></sub></p>

## <a name="fast-twitch.routing/proxy">`proxy`</a>



<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L10-L10">Source</a></sub></p>

## <a name="fast-twitch.routing/response">`response`</a>
``` clojure
(response body)
```
Function.

Builds a 200 response map with the supplied body.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L12-L17">Source</a></sub></p>

## <a name="fast-twitch.routing/response?">`response?`</a>
``` clojure
(response? response)
```
Function.

Returns true when a value matches the expected response map shape.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L40-L45">Source</a></sub></p>

## <a name="fast-twitch.routing/routes">`routes`</a>
``` clojure
(routes routes default-handler)
```
Function.

Builds a dispatching handler from route definitions and a fallback handler.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L269-L285">Source</a></sub></p>

## <a name="fast-twitch.routing/run-adapter">`run-adapter`</a>
``` clojure
(run-adapter app)
(run-adapter handler options)
```
Function.

Starts the runtime adapter for an application or handler.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L287-L292">Source</a></sub></p>

## <a name="fast-twitch.routing/status">`status`</a>
``` clojure
(status status)
(status response status)
```
Function.

Creates a bare response for a status code or updates an existing response map.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L19-L26">Source</a></sub></p>

## <a name="fast-twitch.routing/url-pattern">`url-pattern`</a>
``` clojure
(url-pattern pathname)
```
Function.

Builds a URLPattern that matches the given pathname.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/routing.cljs#L47-L50">Source</a></sub></p>

-----
# <a name="fast-twitch.util.anti-forgery">fast-twitch.util.anti-forgery</a>


View helpers for rendering anti-forgery form fields.




## <a name="fast-twitch.util.anti-forgery/anti-forgery-field">`anti-forgery-field`</a>
``` clojure
(anti-forgery-field)
```
Function.

Returns a hidden form input populated with the current anti-forgery token.
<p><sub><a href="https://github.com/OUTCASTGEEK-TECH/fast-twitch/blob/main/src/fast_twitch/util/anti_forgery.cljs#L5-L11">Source</a></sub></p>
