(ns fast-twitch.util.validation)

(defn fail!
  [code operation path expected]
  (throw
    (ex-info
      (str (name operation) ": invalid " (pr-str path))
      {:code code :operation operation :direction :input :path path :expected expected})))

(defn capability!
  [capabilities operation]
  (let [native (get capabilities operation)]
    (if (fn? native)
      native
      (fail! :fast-twitch.contract/unsupported-capability
             operation
             []
             :native-operation))))
