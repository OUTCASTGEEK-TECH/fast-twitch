(ns utils.macros)

(defmacro env-var [v]
  `(.. js/process -env ~(symbol (str "-" v))))

