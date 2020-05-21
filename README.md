# fast-twitch

[Documentation](https://outcastgeek-tech.github.io/fast-twitch-documentation/)

**FastTwitch** is intended to provide a clojurescript friendly API 
to popular NodeJs / Deno http servers. The goal is to play nice with
the existing javascript ecosystem, and replace the javascript tools
and APIs, with clojurescript equivalents when possible to avoid bloat.

## Using `fast-twitch`

```sh
# clone the FastTwitch Examples
git clone https://github.com/OUTCASTGEEK-TECH/fast-twitch-examples

cd fast-twitch-examples/basic && npm install && npm run start-dev
#then navigate to http://localhost:2121

#or

cd fast-twitch-examples/ft-web && npm install && npm run start-dev
#then navigate to http://localhost:2222

```

## Features

* Isomorphic / Universal Rendering
* Endpoints Produce __html / json / transit__ 
* __CoreAsync__ support for your endpoints
* Support for all of __ExpressJs __functionality (WIP)
* __ShadowCljs__ for compilation


## Roadmap

* Improve Documentation (on going)
* Add Deno support
* WASM Utilities
* Support for additional NodeJs / Deno servers: __koa, hapi, oak__ etc
