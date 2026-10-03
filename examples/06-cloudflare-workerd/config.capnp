using Workerd = import "/workerd/workerd.capnp";

const example :Workerd.Config = (
  services = [
    (name = "main", worker = (
      modules = [(name = "worker.mjs", esModule = embed "target/worker.mjs")],
      compatibilityDate = "2026-10-03"
    ))
  ],
  sockets = [
    (name = "http", address = "127.0.0.1:8787", http = (), service = "main")
  ]
);
