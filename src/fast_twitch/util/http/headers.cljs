(ns fast-twitch.util.http.headers
  (:require [clojure.string :as str])
  (:refer-global :only [Headers]))

(defn headers->map
  [headers]
  (into {}
        (map (fn [e] [(aget e 0) (aget e 1)]))
        (.entries headers)))

(defn lossless-map
  [headers]
  (let [m (headers->map headers)
        getter (aget headers "getSetCookie")
        cookies (when getter (.call getter headers))]
    (if (and cookies (pos? (.-length cookies))) (assoc m "set-cookie" (vec cookies)) m)))

(defn ring-entries
  "Projects each Ring header value as one entry, leaving native coercion intact."
  [headers]
  (map (fn [[k v]] [(name k) v])
    headers))

(defn entries
  [headers]
  (into-array (mapcat (fn [[k v]]
                        (map #(array (name k) (str %)) (if (vector? v) v [v])))
                headers)))

(defn native
  [headers]
  (Headers. (entries headers)))

(defn normalized
  [headers]
  (reduce (fn [m [k v]]
            (let [k (str/lower-case (name k))]
              (when (contains? m k)
                (throw (ex-info "Duplicate header names"
                                {:code :fast-twitch.contract/invalid-option
                                 :path [:headers k]})))
              (assoc m k v)))
    {}
    headers))

(defn edit-deltas
  [baseline current]
  (reduce-kv
    (fn [out lower entries]
      (let [changed (remove (fn [[k v]]
                              (and (contains? baseline k) (= v (get baseline k))))
                      entries)
            selected (cond (= 1 (count entries)) (first entries)
                           (= 1 (count changed)) (first changed)
                           :else (throw (ex-info
                                          "Ambiguous case-insensitive header edit"
                                          {:code :fast-twitch.http/ambiguous-header-edit
                                           :path [:headers lower]})))]
        (assoc out lower (second selected))))
    {}
    (group-by (fn [[k _]]
                (str/lower-case (name k)))
              current)))
