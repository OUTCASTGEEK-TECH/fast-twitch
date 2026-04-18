(ns fast-twitch.middlewares.content-security-policy
  "Applies Content Security Policy headers from structured directive data."
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [URL]])

(def csp-directive-default-src
  "The default-src CSP directive name."
  "default-src")

(def csp-directive-font-src
  "The font-src CSP directive name."
  "font-src")

(def csp-directive-frame-src
  "The frame-src CSP directive name."
  "frame-src")

(def csp-directive-img-src
  "The img-src CSP directive name."
  "img-src")

(def csp-directive-script-src
  "The script-src CSP directive name."
  "script-src")

(def csp-directive-style-src
  "The style-src CSP directive name."
  "style-src")

(def csp-directive-media-src
  "The media-src CSP directive name."
  "media-src")

(def csp-directive-worker-src
  "The worker-src CSP directive name."
  "worker-src")

(def csp-directive-connect-src
  "The connect-src CSP directive name."
  "connect-src")

(def csp-directive-base-uri
  "The base-uri CSP directive name."
  "base-uri")

(def csp-directive-object-src
  "The object-src CSP directive name."
  "object-src")

(def csp-source-self
  "The CSP source expression for the current origin."
  "'self'")

(def csp-source-none
  "The CSP source expression that blocks every source."
  "'none'")

(def csp-source-unsafe-eval
  "The CSP source expression that allows eval-like script execution."
  "'unsafe-eval'")

(def csp-source-unsafe-inline
  "The CSP source expression that allows inline script or style content."
  "'unsafe-inline'")

(def csp-source-data
  "The CSP source expression for data: URLs."
  "data:")

(def csp-source-blob
  "The CSP source expression for blob: URLs."
  "blob:")

(def csp-source-https
  "The CSP source expression for all HTTPS origins."
  "https:")

(def csp-source-jsdelivr
  "The jsDelivr CDN source expression."
  "https://cdn.jsdelivr.net")

(def csp-source-fonts-googleapis
  "The Google Fonts stylesheet source expression."
  "https://fonts.googleapis.com")

(def csp-source-fonts-gstatic
  "The Google Fonts asset source expression."
  "https://fonts.gstatic.com")

(def default-directive-order
  "The stable directive order used when rendering CSP policy strings."
  [csp-directive-default-src
   csp-directive-font-src
   csp-directive-frame-src
   csp-directive-img-src
   csp-directive-script-src
   csp-directive-style-src
   csp-directive-media-src
   csp-directive-worker-src
   csp-directive-connect-src
   csp-directive-base-uri
   csp-directive-object-src])

(defn default-csp-config
  "Returns the shared baseline CSP configuration."
  []
  {:directives {csp-directive-default-src [csp-source-self]
                csp-directive-font-src [csp-source-self csp-source-data csp-source-fonts-gstatic]
                csp-directive-frame-src [csp-source-none]
                csp-directive-img-src [csp-source-self csp-source-data]
                csp-directive-script-src [csp-source-self csp-source-jsdelivr]
                csp-directive-style-src [csp-source-self csp-source-unsafe-inline csp-source-fonts-googleapis]}
   :allow-origin-sources []})

(defn csp-sources
  "Builds an append-mode source input for a directive."
  [& sources]
  {:mode :append
   :sources sources})

(defn replace-csp-sources
  "Builds a replace-mode source input for a directive."
  [& sources]
  {:mode :replace
   :sources sources})

(defn- source-input?
  "Returns true when x looks like a source input map."
  [x]
  (and (map? x)
       (or (contains? x :sources)
           (contains? x :mode))))

(defn- normalize-source
  "Normalizes one CSP source string."
  [source]
  (let [source (str/trim (str source))]
    (cond
      (str/blank? source) nil
      (or (str/includes? source "://")
          (str/starts-with? source "'")
          (str/ends-with? source ":"))
      source
      :else
      (str "https://" source))))

(defn normalize-csp-sources
  "Normalizes CSP source expressions while dropping blanks."
  [sources]
  (->> sources
       (keep normalize-source)
       (into [])))

(defn append-unique-sources
  "Appends source expressions while preserving first occurrence order."
  [base extras]
  (loop [result []
         seen #{}
         values (concat base extras)]
    (if-let [value (first values)]
      (let [value (str/trim (str value))]
        (if (or (str/blank? value) (contains? seen value))
          (recur result seen (next values))
          (recur (conj result value) (conj seen value) (next values))))
      result)))

(defn sanitize-exclusive-none-source
  "Removes 'none' when a directive has other source expressions."
  [sources]
  (if (<= (count sources) 1)
    sources
    (let [sources (remove #{csp-source-none} sources)]
      (if (seq sources)
        (vec sources)
        [csp-source-none]))))

(defn merge-csp-sources
  "Merges source expressions with normalization and duplicate removal."
  [base & extras]
  (sanitize-exclusive-none-source
   (append-unique-sources base (normalize-csp-sources extras))))

(defn- update-source-list
  "Applies a source input to an existing source list."
  [base input]
  (let [input (cond
                (source-input? input) input
                (sequential? input) {:mode :append :sources input}
                :else {:mode :append :sources [input]})]
    (if (= :replace (:mode input))
      (apply merge-csp-sources nil (:sources input))
      (apply merge-csp-sources base (:sources input)))))

(defn apply-directive-sources
  "Applies a source input to one directive in a CSP config."
  [config directive input]
  (let [directive (str/trim (str directive))]
    (if (str/blank? directive)
      config
      (update-in config [:directives directive] update-source-list input))))

(defn apply-allow-origin-sources
  "Applies a source input to the allowed-origin reflection list."
  [config input]
  (update config :allow-origin-sources update-source-list input))

(defn apply-asset-base-urls
  "Adds asset base URLs to image/media sources and allowed-origin matching."
  [config asset-base-urls]
  (if (seq asset-base-urls)
    (-> config
        (apply-directive-sources csp-directive-img-src
                                 (apply csp-sources csp-source-https asset-base-urls))
        (apply-directive-sources csp-directive-media-src
                                 (apply csp-sources csp-source-self csp-source-https asset-base-urls))
        (apply-allow-origin-sources (apply csp-sources asset-base-urls)))
    config))

(defn update-csp-config
  "Applies structured CSP inputs to a base config without mutating it."
  [base & inputs]
  (reduce
   (fn [config input]
     (let [config (reduce (fn [config [directive source-input]]
                            (apply-directive-sources config directive source-input))
                          config
                          (:directives input))]
       (cond-> config
         (:allow-origin-sources input)
         (apply-allow-origin-sources (:allow-origin-sources input))

         (:asset-base-urls input)
         (apply-asset-base-urls (:asset-base-urls input)))))
   {:directives (into {} (:directives base))
    :allow-origin-sources (vec (:allow-origin-sources base))}
   inputs))

(defn build-csp-config
  "Applies structured CSP inputs to the default CSP configuration."
  [& inputs]
  (apply update-csp-config (default-csp-config) inputs))

(defn csp-directive
  "Renders one CSP directive to a policy fragment."
  [directive sources]
  (let [directive (str/trim (str directive))
        sources (append-unique-sources nil sources)]
    (when (and (seq directive) (seq sources))
      (str directive " " (str/join " " sources)))))

(defn build-csp-policy
  "Renders a CSP config into a Content-Security-Policy header value."
  [config]
  (let [directives (:directives config)
        ordered (keep #(csp-directive % (get directives %))
                      default-directive-order)
        known (set default-directive-order)
        extras (->> (keys directives)
                    (remove known)
                    sort
                    (keep #(csp-directive % (get directives %))))]
    (str/join "; " (concat ordered extras))))

(defn origin-from-source
  "Returns the URL origin for a CSP source expression when it has one."
  [source]
  (try
    (when (str/includes? (str source) "://")
      (aget (URL. source) "origin"))
    (catch :default _
      nil)))

(defn origin-allowed?
  "Returns true when an origin matches one of the configured allowed sources."
  [origin allowed-sources]
  (some #(= origin (origin-from-source %))
        (normalize-csp-sources allowed-sources)))

(defn content-security-policy-response
  "Adds CSP and optional reflected Access-Control-Allow-Origin headers."
  ([response request config]
   (content-security-policy-response response request config {}))
  ([response request config options]
   (let [policy (build-csp-policy config)
         response (if (seq policy)
                    (common/assoc-header
                     response
                     (or (:header-name options) "Content-Security-Policy")
                     policy)
                    response)
         origin (some-> (common/header-value (:headers request) :origin) str/trim)]
     (if (and (seq origin)
              (origin-allowed? origin (:allow-origin-sources config)))
       (common/assoc-header response "Access-Control-Allow-Origin" origin)
       response))))

(defn wrap-content-security-policy
  "Wraps a handler so responses receive a structured Content Security Policy."
  ([handler]
   (wrap-content-security-policy handler (default-csp-config)))
  ([handler config]
   (wrap-content-security-policy handler config {}))
  ([handler config options]
   (common/wrap-response
    handler
    #(content-security-policy-response %1 %2 config options))))
