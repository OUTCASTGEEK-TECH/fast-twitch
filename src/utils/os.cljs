(ns util.os
  (:require [cljs.nodejs :as nodejs]))

;; (defn env!
;;   "Returns the value of the environment variable k,
;;     or raises if k is missing from the environment."
;;   [k]
;;   (let [e (js->clj (aget nodejs/process "env"))]
;;     (or (get e k) (throw (str "missing key " k)))))

;; (defn env
;;   "Returns the value of the environment variable k,
;;     or raises if k is missing from the environment."
;;   [k]
;;   (let [e (js->clj (aget nodejs/process "env"))]
;;     (or (get e k) nil)))

(defn trap
  "Trap the Unix signal sig with the given function."
  [sig f]
  (.on nodejs/process (str "SIG" sig) f))

(defn exit
  "Exit with the given status."
  [status]
  (.exit nodejs/process status))
