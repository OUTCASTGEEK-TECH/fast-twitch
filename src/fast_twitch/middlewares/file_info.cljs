(ns fast-twitch.middlewares.file-info
  "Adds metadata-derived headers for file-backed responses and applies cache helpers."
  [:require
   [fast-twitch.middlewares.common :as common]
   [fast-twitch.middlewares.content-type :as content-type]
   [fast-twitch.middlewares.not-modified :as not-modified]]
  [:refer-global :only [Date]])

(defn- date-string
  "Formats a date-like value as a UTC string."
  [value]
  (cond
    (nil? value) nil
    (string? value) value
    (number? value) (.toUTCString (Date. value))
    :else (.toUTCString value)))

(defn- file-body
  "Returns the response body when it looks like file metadata."
  [response]
  (let [body (:body response)]
    (when (map? body)
      body)))

(defn file-info-response
  "Adds file-derived headers and cache helpers to a response."
  ([response request]
   (file-info-response response request {}))
  ([response request options]
   (let [file (file-body response)
         response (cond-> response
                    (and file (:size file)
                         (not (common/has-header? (:headers response) :content-length)))
                    (common/assoc-header "Content-Length" (str (:size file)))

                    (and file (or (:last-modified file) (:mtime file))
                         (not (common/has-header? (:headers response) :last-modified)))
                    (common/assoc-header "Last-Modified"
                                         (date-string (or (:last-modified file)
                                                          (:mtime file)))))]
     (-> response
         (content-type/content-type-response request options)
         (not-modified/not-modified-response request)))))

(defn wrap-file-info
  "Wraps a handler so file-like responses gain metadata and cache headers."
  ([handler]
   (wrap-file-info handler {}))
  ([handler options]
   (common/wrap-response handler #(file-info-response %1 %2 options))))
