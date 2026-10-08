(ns workerd-example.worker
  (:require [fast-twitch.routing :as routing]))

(defn hello
  [_request]
  {:status 200
   :headers {:content-type "text/plain; charset=utf-8"
             :x-runtime "cloudflare-workerd"
             :set-cookie ["first=1; Path=/; HttpOnly" "second=2; Path=/; HttpOnly"]}
   :body ["Hello from Fast-Twitch on Cloudflare workerd!\n"
          "This response has two separate Set-Cookie headers.\n"]})

(def routes
  (routing/routes [{:pattern "/" :method :get :handler hello}
                   {:pattern "/hello/:name"
                    :method :get
                    :handler
                      (fn [request]
                        (routing/response
                          (str "Hello, " (get-in request [:path-params :name]) "!\n")))}
                   {:pattern "/echo"
                    :method :post
                    :handler (fn [request]
                               ;; Fast-Twitch preserves the original Fetch Request in
                               ;; its map.
                               (.then (.text (::routing/request request))
                                      (fn [body]
                                        (routing/response body))))}]
                  (fn [_request]
                    (routing/not-found "Not found\n"))))

(def handler
  (routing/ft-handler routes {:protocol "HTTP/1.1" :remote-addr "127.0.0.1"}))

(defn ^:export fetch
  [request _env _ctx]
  (handler request))
