(ns fast-twitch.middlewares.file
  "Serves files from a local path with runtime-specific filesystem access and path safety checks."
  [:require-macros [fast-twitch.macros :refer [current-runtime]]]
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Date Error Promise decodeURIComponent globalThis]])

(defn- url-root-path
  "Normalizes a configured URL root into a single leading-slash path segment."
  [url-root]
  (when url-root
    (str "/" (-> url-root
                 (str/replace #"^/+" "")
                 (str/replace #"/+$" "")))))

(defn- file-request?
  "Returns true when the request is eligible for static file handling."
  [request options]
  (and (#{:get :head} (:request-method request))
       (if-let [root (url-root-path (:url-root options))]
         (let [uri (:uri request)]
           (or (= uri root)
               (str/starts-with? uri (str root "/"))))
         true)))

(defn- request-path
  "Extracts the path portion to resolve under the configured file root."
  [request options]
  (let [uri (:uri request)]
    (if-let [root (url-root-path (:url-root options))]
      (when (or (= uri root)
                (str/starts-with? uri (str root "/")))
        (subs uri (count root)))
      uri)))

(defn- decode-path
  "Decodes a percent-encoded request path, returning nil on decode failure."
  [path]
  (try
    (decodeURIComponent (or path ""))
    (catch :default _
      nil)))

(defn- path-segments
  "Splits a path into non-empty, non-dot segments."
  [path]
  (->> (str/split (or path "") #"/+")
       (remove #(or (str/blank? %) (= "." %)))))

(defn- safe-path?
  "Returns true when no path segment escapes upward or contains a null byte."
  [segments]
  (not-any? #(or (= ".." %)
                 (str/includes? % "\u0000"))
            segments))

(defn- dotfile-path?
  "Returns true when any segment points at a dotfile or dot-directory."
  [segments]
  (some #(str/starts-with? % ".") segments))

(defn- normalized-root-path
  "Removes trailing slashes from the configured filesystem root."
  [root-path]
  (str/replace root-path #"/+$" ""))

(defn- join-path
  "Builds an absolute file path from the root path and safe path segments."
  [root-path segments]
  (let [root-path (normalized-root-path root-path)]
    (if (seq segments)
      (str root-path "/" (str/join "/" segments))
      root-path)))

(defn- file-path
  "Resolves the filesystem path for a request when it passes safety checks."
  [request root-path options]
  (when-let [path (some-> (request-path request options) decode-path)]
    (let [segments (path-segments path)]
      (when (and (safe-path? segments)
                 (or (:show-dotfiles? options)
                     (not (dotfile-path? segments))))
        (join-path root-path segments)))))

(defn- not-found-error?
  "Returns true when an error represents a missing filesystem entry."
  [error]
  (let [name (aget error "name")
        code (aget error "code")]
    (or (= "NotFound" name)
        (= "NotFoundError" name)
        (= "ENOENT" code)
        (= "ENOTDIR" code))))

(defn- node-fs
  "Loads Node's promise-based filesystem module."
  []
  (js/require "node:fs/promises"))

(defn- stat-file
  "Stats a file path and returns normalized metadata for the active runtime."
  [file-path]
  (case (current-runtime)
    :deno
    (-> (.stat js/Deno file-path)
        (.then (fn [stat]
                 {:path file-path
                  :file? (aget stat "isFile")
                  :directory? (aget stat "isDirectory")
                  :size (aget stat "size")
                  :last-modified (aget stat "mtime")})))

    :bun
    (let [file (.file js/Bun file-path)]
      (-> (.exists file)
          (.then (fn [exists?]
                   (when exists?
                     {:path file-path
                      :file? true
                      :directory? false
                      :size (aget file "size")
                      :last-modified (when-let [ms (aget file "lastModified")]
                                       (Date. ms))
                      :bun-file file})))))

    :node
    (let [fs (node-fs)]
      (-> (.stat fs file-path)
          (.then (fn [stat]
                   {:path file-path
                    :file? (.isFile stat)
                    :directory? (.isDirectory stat)
                    :size (aget stat "size")
                    :last-modified (aget stat "mtime")}))))

    (Promise.reject (Error. "No supported file server runtime found"))))

(defn- maybe-stat-file
  "Returns file metadata or nil when the path does not exist."
  [file-path]
  (-> (stat-file file-path)
      (.catch (fn [error]
                (if (not-found-error? error)
                  nil
                  (throw error))))))

(defn- index-path
  "Builds the default index.html path for a directory."
  [file-path]
  (str (str/replace file-path #"/+$" "") "/index.html"))

(defn- resolve-file
  "Resolves a file path to either a direct file or an index file in a directory."
  [file-path options]
  (-> (maybe-stat-file file-path)
      (.then
       (fn [entry]
         (cond
           (:file? entry)
           entry

           (and (:directory? entry)
                (get options :index-files? true))
           (maybe-stat-file (index-path file-path))

           :else
           nil)))))

(defn- read-file-async
  "Reads the contents of a resolved file entry for the active runtime."
  [entry]
  (case (current-runtime)
    :deno
    (.readFile js/Deno (:path entry))

    :bun
    (Promise.resolve (:bun-file entry))

    :node
    (.readFile (node-fs) (:path entry))

    (Promise.reject (Error. "No supported file server runtime found"))))

(defn- date-string
  "Formats a file date value as a UTC string."
  [date]
  (cond
    (nil? date) nil
    (number? date) (.toUTCString (Date. date))
    :else (.toUTCString date)))

(defn- option-headers
  "Normalizes configured headers into a plain map."
  [headers]
  (cond
    (map? headers)
    headers

    (sequential? headers)
    (into {}
          (keep (fn [header]
                  (when-let [idx (str/index-of header ":")]
                    [(subs header 0 idx)
                     (str/trim (subs header (inc idx)))])))
          headers)

    :else
    {}))

(defn- file-response
  "Builds a file response map from a resolved file entry and file contents."
  [request entry body options]
  (let [headers (cond-> (option-headers (:headers options))
                  (:size entry)
                  (assoc "Content-Length" (str (:size entry)))

                  (:last-modified entry)
                  (assoc "Last-Modified" (date-string (:last-modified entry))))]
    {:status 200
     :headers headers
     :body (when-not (= :head (:request-method request))
             body)}))

(defn file-request
  "Attempts to serve a file for the request and returns nil when nothing matches."
  ([request root-path]
   (file-request request root-path {}))
  ([request root-path options]
   (if (file-request? request options)
     (if-let [file-path (file-path request root-path options)]
       (-> (resolve-file file-path options)
           (.then (fn [entry]
                    (when entry
                      (-> (read-file-async entry)
                          (.then #(file-response request entry % options))))))
           (.then identity))
       (common/promise nil))
     (common/promise nil))))

(defn wrap-file
  "Wraps a handler with filesystem-backed static file serving."
  ([handler root-path]
   (wrap-file handler root-path {}))
  ([handler root-path options]
   (fn
     ([request]
      (if (:prefer-handler? options)
        (handler request)
        (if (file-request? request options)
          (-> (file-request request root-path options)
              (.then #(or % (handler request))))
          (handler request))))
     ([request respond raise]
      (if (:prefer-handler? options)
        (handler request respond raise)
        (if (file-request? request options)
          (-> (file-request request root-path options)
              (.then (fn [response]
                       (if response
                         (respond response)
                         (handler request respond raise))))
              (.catch raise))
          (handler request respond raise)))))))
