(ns fast-twitch.middlewares.file
  [:require-macros [fast-twitch.macros :refer [current-runtime]]]
  [:require
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Date Error Promise decodeURIComponent globalThis]])

(defn- url-root-path [url-root]
  (when url-root
    (str "/" (-> url-root
                 (str/replace #"^/+" "")
                 (str/replace #"/+$" "")))))

(defn- file-request? [request options]
  (and (#{:get :head} (:request-method request))
       (if-let [root (url-root-path (:url-root options))]
         (let [uri (:uri request)]
           (or (= uri root)
               (str/starts-with? uri (str root "/"))))
         true)))

(defn- request-path [request options]
  (let [uri (:uri request)]
    (if-let [root (url-root-path (:url-root options))]
      (when (or (= uri root)
                (str/starts-with? uri (str root "/")))
        (subs uri (count root)))
      uri)))

(defn- decode-path [path]
  (try
    (decodeURIComponent (or path ""))
    (catch :default _
      nil)))

(defn- path-segments [path]
  (->> (str/split (or path "") #"/+")
       (remove #(or (str/blank? %) (= "." %)))))

(defn- safe-path? [segments]
  (not-any? #(or (= ".." %)
                 (str/includes? % "\u0000"))
            segments))

(defn- dotfile-path? [segments]
  (some #(str/starts-with? % ".") segments))

(defn- normalized-root-path [root-path]
  (str/replace root-path #"/+$" ""))

(defn- join-path [root-path segments]
  (let [root-path (normalized-root-path root-path)]
    (if (seq segments)
      (str root-path "/" (str/join "/" segments))
      root-path)))

(defn- file-path [request root-path options]
  (when-let [path (some-> (request-path request options) decode-path)]
    (let [segments (path-segments path)]
      (when (and (safe-path? segments)
                 (or (:show-dotfiles? options)
                     (not (dotfile-path? segments))))
        (join-path root-path segments)))))

(defn- not-found-error? [error]
  (let [name (aget error "name")
        code (aget error "code")]
    (or (= "NotFound" name)
        (= "NotFoundError" name)
        (= "ENOENT" code)
        (= "ENOTDIR" code))))

(defn- node-fs []
  (js/require "node:fs/promises"))

(defn- stat-file [file-path]
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

(defn- maybe-stat-file [file-path]
  (-> (stat-file file-path)
      (.catch (fn [error]
                (if (not-found-error? error)
                  nil
                  (throw error))))))

(defn- index-path [file-path]
  (str (str/replace file-path #"/+$" "") "/index.html"))

(defn- resolve-file [file-path options]
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

(defn- read-file-async [entry]
  (case (current-runtime)
    :deno
    (.readFile js/Deno (:path entry))

    :bun
    (Promise.resolve (:bun-file entry))

    :node
    (.readFile (node-fs) (:path entry))

    (Promise.reject (Error. "No supported file server runtime found"))))

(defn- date-string [date]
  (cond
    (nil? date) nil
    (number? date) (.toUTCString (Date. date))
    :else (.toUTCString date)))

(defn- option-headers [headers]
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

(defn- file-response [request entry body options]
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
