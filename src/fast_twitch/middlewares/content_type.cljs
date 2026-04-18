(ns fast-twitch.middlewares.content-type
  "Infers content types from filenames, bytes, and file metadata for responses and uploads."
  [:require
   [cljs.nodejs :as nodejs]
   [clojure.string :as str]
   [fast-twitch.middlewares.common :as common]]
  [:refer-global :only [Promise Uint8Array]])

(def content-type
  (aget (nodejs/require "@std/media-types/content-type")
        "contentType"))

(def starts-with
  (aget (nodejs/require "@std/bytes/starts-with")
        "startsWith"))

;; WHATWG MIME Sniffing defines the resource header as up to 1445 bytes.
;; https://mimesniff.spec.whatwg.org/#reading-the-resource-header
(def resource-header-size 1445)

(defn- u8
  "Creates a Uint8Array from numeric byte values."
  [& xs]
  (Uint8Array. (clj->js xs)))

(defn- ascii-u8
  "Creates a Uint8Array from the ASCII bytes of a string."
  [s]
  (Uint8Array.
   (clj->js (map #(.charCodeAt s %) (range (count s))))))

(defn- byte-length
  "Returns the number of bytes in a byte array, or zero when absent."
  [bytes]
  (if bytes
    (aget bytes "length")
    0))

(defn- byte-at
  "Returns the byte value at the given index."
  [bytes idx]
  (aget bytes idx))

(defn- whitespace-byte?
  "Returns true when a byte is treated as leading ASCII whitespace."
  [b]
  (or (= b 0x09)
      (= b 0x0a)
      (= b 0x0c)
      (= b 0x0d)
      (= b 0x20)))

(defn- binary-data-byte?
  "Returns true when a byte strongly suggests binary rather than text data."
  [b]
  (or (<= 0x00 b 0x08)
      (= b 0x0b)
      (<= 0x0e b 0x1a)
      (<= 0x1c b 0x1f)))

(defn- tag-terminating-byte?
  "Returns true when a byte can terminate an HTML or SVG tag prefix match."
  [b]
  (or (= b 0x20)
      (= b 0x3e)))

(defn- lower-ascii-byte
  "Lowercases an ASCII uppercase byte without affecting other bytes."
  [b]
  (if (<= 0x41 b 0x5a)
    (+ b 0x20)
    b))

(def prefix-content-types
  [[(u8 0x00 0x00 0x01 0x00) "image/vnd.microsoft.icon"]
   [(u8 0x00 0x00 0x02 0x00) "image/vnd.microsoft.icon"]
   [(ascii-u8 "BM") "image/bmp"]
   [(u8 0xff 0xd8 0xff) "image/jpeg"]
   [(ascii-u8 "GIF87a") "image/gif"]
   [(ascii-u8 "GIF89a") "image/gif"]
   [(u8 0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a) "image/png"]
   [(ascii-u8 "%PDF-") "application/pdf"]
   [(ascii-u8 "%!PS-Adobe-") "application/postscript"]
   [(u8 0x00 0x01 0x00 0x00) "font/ttf"]
   [(ascii-u8 "OTTO") "font/otf"]
   [(ascii-u8 "ttcf") "font/collection"]
   [(ascii-u8 "wOFF") "font/woff"]
   [(ascii-u8 "wOF2") "font/woff2"]
   [(ascii-u8 "ID3") "audio/mpeg"]
   [(ascii-u8 "OggS") "application/ogg"]
   [(ascii-u8 "MThd") "audio/midi"]
   [(u8 0x1f 0x8b 0x08) "application/x-gzip"]
   [(ascii-u8 "BZh") "application/x-bzip2"]
   [(u8 0x28 0xb5 0x2f 0xfd) "application/zstd"]
   [(u8 0x50 0x4b 0x03 0x04) "application/zip"]
   [(u8 0x52 0x61 0x72 0x21 0x1a 0x07 0x00)
    "application/x-rar-compressed"]
   [(u8 0x37 0x7a 0xbc 0xaf 0x27 0x1c)
    "application/x-7z-compressed"]
   [(u8 0x00 0x61 0x73 0x6d) "application/wasm"]
   [(u8 0xfe 0xff) "text/plain"]
   [(u8 0xff 0xfe) "text/plain"]
   [(u8 0xef 0xbb 0xbf) "text/plain"]])

(def html-tag-prefixes
  (mapv ascii-u8
        ["<!doctype html"
         "<html"
         "<head"
         "<script"
         "<iframe"
         "<h1"
         "<div"
         "<font"
         "<table"
         "<a"
         "<style"
         "<title"
         "<b"
         "<body"
         "<br"
         "<p"]))

(def svg-tag-prefix
  (ascii-u8 "<svg"))

(def xml-prefix
  (ascii-u8 "<?xml"))

(def riff-prefix
  (ascii-u8 "RIFF"))

(def webp-prefix
  (ascii-u8 "WEBP"))

(def webp-chunk-prefix
  (ascii-u8 "VP"))

(def wave-prefix
  (ascii-u8 "WAVE"))

(def avi-prefix
  (ascii-u8 "AVI "))

(def form-prefix
  (ascii-u8 "FORM"))

(def aiff-prefix
  (ascii-u8 "AIFF"))

(def aifc-prefix
  (ascii-u8 "AIFC"))

(def ftyp-prefix
  (ascii-u8 "ftyp"))

(def mp4-brand-prefix
  (ascii-u8 "mp4"))

(def ebml-prefix
  (u8 0x1a 0x45 0xdf 0xa3))

(def webm-doctype
  (ascii-u8 "webm"))

(def tar-prefix
  (ascii-u8 "ustar"))

(def eot-prefix
  (ascii-u8 "LP"))

(defn base-content-type
  "Strips parameters from a content type and lowercases the main media type."
  [s]
  (when (seq s)
    (-> s
        (str/split #";" 2)
        first
        str/lower-case
        str/trim)))

(defn filename-extension
  "Returns the extension portion of a filename, including the leading dot."
  [filename]
  (when-let [idx (str/last-index-of (or filename "") ".")]
    (subs filename idx)))

(defn- extension
  "Extracts the extension from the final path segment."
  [path]
  (when-let [file-name (last (str/split (or path "") "/"))]
    (filename-extension file-name)))

(defn- body-path
  "Returns a useful filename or path from a file-like response body."
  [body]
  (when (map? body)
    (or (:path body) (:filename body))))

(defn- mime-type
  "Chooses a content type from explicit mappings, extension lookup, or a binary fallback."
  [path options]
  (let [ext (extension path)
        mime-types (:mime-types options)]
    (or (get mime-types ext)
        (get mime-types (some-> ext (subs 1)))
        (when ext (content-type ext))
        "application/octet-stream")))

(defn expected-content-type
  "Looks up the extension-derived content type for a filename."
  [filename]
  (when-let [ext (filename-extension filename)]
    (base-content-type (content-type ext))))

(defn file-bytes
  "Reads the leading bytes used for file content sniffing."
  [file]
  (if (and file (aget file "slice") (aget file "arrayBuffer"))
    (-> (.arrayBuffer (.slice file 0 resource-header-size))
        (.then #(Uint8Array. %)))
    (Promise.resolve nil)))

(defn bytes-at?
  "Returns true when the bytes at an offset match the given signature."
  [bytes offset signature]
  (and bytes
       signature
       (<= (+ offset (byte-length signature)) (byte-length bytes))
       (starts-with (.subarray bytes offset) signature)))

(defn bytes-start-with?
  "Returns true when the byte sequence starts with the given signature."
  [bytes signature]
  (bytes-at? bytes 0 signature))

(defn- bytes-ci-at?
  "Performs a case-insensitive signature match at the given offset."
  [bytes offset signature]
  (and bytes
       signature
       (<= (+ offset (byte-length signature)) (byte-length bytes))
       (every? (fn [idx]
                 (= (lower-ascii-byte (byte-at bytes (+ offset idx)))
                    (lower-ascii-byte (byte-at signature idx))))
               (range (byte-length signature)))))

(defn- leading-content-offset
  "Skips leading whitespace bytes and returns the first content offset."
  [bytes]
  (loop [idx 0]
    (if (and (< idx (byte-length bytes))
             (whitespace-byte? (byte-at bytes idx)))
      (recur (inc idx))
      idx)))

(defn- tag-prefix?
  "Checks whether a tag-like prefix appears at an offset and ends cleanly."
  [bytes offset signature]
  (let [end (+ offset (byte-length signature))]
    (and (bytes-ci-at? bytes offset signature)
         (< end (byte-length bytes))
         (tag-terminating-byte? (byte-at bytes end)))))

(defn- prefix-content-type
  "Matches well-known file signatures against the leading bytes."
  [bytes]
  (some (fn [[signature mime-type]]
          (when (bytes-start-with? bytes signature)
            mime-type))
        prefix-content-types))

(defn- scriptable-content-type
  "Detects HTML, SVG, and XML content from leading markup."
  [bytes]
  (let [offset (leading-content-offset bytes)]
    (cond
      (tag-prefix? bytes offset svg-tag-prefix)
      "image/svg+xml"

      (some #(tag-prefix? bytes offset %) html-tag-prefixes)
      "text/html"

      (bytes-at? bytes offset xml-prefix)
      "application/xml")))

(defn- riff-content-type
  "Detects RIFF-based formats such as WebP, WAV, and AVI."
  [bytes]
  (when (bytes-start-with? bytes riff-prefix)
    (cond
      (and (bytes-at? bytes 8 webp-prefix)
           (bytes-at? bytes 12 webp-chunk-prefix))
      "image/webp"

      (bytes-at? bytes 8 wave-prefix)
      "audio/wave"

      (bytes-at? bytes 8 avi-prefix)
      "video/avi")))

(defn- aiff-content-type
  "Detects AIFF-family audio containers."
  [bytes]
  (when (bytes-start-with? bytes form-prefix)
    (cond
      (bytes-at? bytes 8 aiff-prefix)
      "audio/aiff"

      (bytes-at? bytes 8 aifc-prefix)
      "audio/aiff")))

(defn- mp3-frame-content-type
  "Detects MPEG audio frames when no ID3 tag is present."
  [bytes]
  (when (and (<= 4 (byte-length bytes))
             (= 0xff (byte-at bytes 0))
             (= 0xe0 (bit-and (byte-at bytes 1) 0xe0))
             (not= 0 (bit-and (bit-shift-right (byte-at bytes 1) 1) 0x03))
             (not= 0x0f (bit-shift-right (bit-and (byte-at bytes 2) 0xf0) 4))
             (not= 0x03 (bit-shift-right (bit-and (byte-at bytes 2) 0x0c) 2)))
    "audio/mpeg"))

(defn- mp4-content-type
  "Detects MP4 files by inspecting the ftyp box and compatible brands."
  [bytes]
  (when (and (<= 12 (byte-length bytes))
             (bytes-at? bytes 4 ftyp-prefix))
    (let [box-size (+ (bit-shift-left (byte-at bytes 0) 24)
                      (bit-shift-left (byte-at bytes 1) 16)
                      (bit-shift-left (byte-at bytes 2) 8)
                      (byte-at bytes 3))
          limit (min box-size (byte-length bytes))]
      (loop [idx 8]
        (cond
          (< limit (+ idx (byte-length mp4-brand-prefix)))
          nil

          (bytes-at? bytes idx mp4-brand-prefix)
          "video/mp4"

          :else
          (recur (+ idx 4)))))))

(defn- webm-content-type
  "Detects WebM content from EBML headers and doctype markers."
  [bytes]
  (when (bytes-start-with? bytes ebml-prefix)
    (let [limit (min 38 (byte-length bytes))]
      (loop [idx 4]
        (cond
          (< limit (+ idx (byte-length webm-doctype)))
          nil

          (bytes-ci-at? bytes idx webm-doctype)
          "video/webm"

          :else
          (recur (inc idx)))))))

(defn- tar-content-type
  "Detects tar archives from the ustar signature."
  [bytes]
  (when (bytes-at? bytes 257 tar-prefix)
    "application/x-tar"))

(defn- eot-content-type
  "Detects embedded OpenType font files."
  [bytes]
  (when (bytes-at? bytes 34 eot-prefix)
    "application/vnd.ms-fontobject"))

(defn- text-bytes?
  "Returns true when the sampled bytes look like plain text."
  [bytes]
  (and bytes
       (pos? (aget bytes "length"))
       (not-any? #(binary-data-byte? (aget bytes %))
               (range (aget bytes "length")))))

(defn sniff-content-type
  "Infers a content type from leading bytes using signature and text heuristics."
  [bytes]
  (or (scriptable-content-type bytes)
      (prefix-content-type bytes)
      (riff-content-type bytes)
      (aiff-content-type bytes)
      (mp4-content-type bytes)
      (webm-content-type bytes)
      (mp3-frame-content-type bytes)
      (tar-content-type bytes)
      (eot-content-type bytes)
      (when (text-bytes? bytes) "text/plain")
      "application/octet-stream"))

(def content-type-aliases
  {"application/gzip" #{"application/x-gzip"}
   "application/x-gzip" #{"application/gzip"}
   "application/vnd.rar" #{"application/x-rar-compressed"}
   "application/x-rar-compressed" #{"application/vnd.rar"}
   "audio/wav" #{"audio/wave" "audio/x-wav"}
   "audio/wave" #{"audio/wav" "audio/x-wav"}
   "audio/x-wav" #{"audio/wav" "audio/wave"}
   "image/x-icon" #{"image/vnd.microsoft.icon"}
   "image/vnd.microsoft.icon" #{"image/x-icon"}})

(defn compatible-content-type?
  "Returns true when two content types should be treated as equivalent."
  [expected sniffed]
  (let [expected (base-content-type expected)
        sniffed (base-content-type sniffed)]
    (or (= expected sniffed)
        (contains? (get content-type-aliases expected #{}) sniffed)
        (contains? (get content-type-aliases sniffed #{}) expected)
        (and expected
             (str/starts-with? expected "text/")
             (= "text/plain" sniffed)))))

(defn content-type-warning
  "Explains the first mismatch found between declared, expected, and sniffed types."
  [declared expected sniffed]
  (cond
    (and expected sniffed
         (not (compatible-content-type? expected sniffed)))
    (str "Content does not match filename extension: expected "
         expected ", sniffed " sniffed)

    (and declared sniffed
         (not (compatible-content-type? declared sniffed)))
    (str "Content does not match declared upload type: declared "
         declared ", sniffed " sniffed)

    (and declared expected
         (not (compatible-content-type? expected declared)))
    (str "Declared upload type does not match filename extension: declared "
         declared ", expected " expected)))

(defn file-content-type-summary
  "Builds an upload summary with declared, expected, sniffed, and warning fields."
  [file]
  (if (map? file)
    (let [filename (:filename file)
          declared (base-content-type (:content-type file))
          expected (or (expected-content-type filename)
                       "application/octet-stream")]
      (-> (file-bytes (:file file))
          (.then (fn [bytes]
                   (let [sniffed (sniff-content-type bytes)]
                     {:filename filename
                      :declared-content-type declared
                      :expected-content-type expected
                      :sniffed-content-type sniffed
                      :content-type-warning
                      (content-type-warning declared expected sniffed)
                      :size (:size file)})))))
    (Promise.resolve
     {:filename "No file selected"
      :declared-content-type "application/octet-stream"
      :expected-content-type "application/octet-stream"
      :sniffed-content-type "application/octet-stream"
      :size 0})))

(defn content-type-response
  "Adds a Content-Type header to responses that do not already specify one."
  ([response request]
   (content-type-response response request {}))
  ([response request options]
   (if (or (nil? (:body response))
           (common/has-header? (:headers response) :content-type))
     response
     (common/assoc-header
      response
      "Content-Type"
      (mime-type (or (body-path (:body response)) (:uri request)) options)))))

(defn wrap-content-type
  "Wraps a handler so missing Content-Type headers are inferred automatically."
  ([handler]
   (wrap-content-type handler {}))
  ([handler options]
   (common/wrap-response handler #(content-type-response %1 %2 options))))
