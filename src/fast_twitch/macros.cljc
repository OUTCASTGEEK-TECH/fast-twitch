(ns fast-twitch.macros)

(defmacro env-var [v]
  `(.. js/process -env ~(symbol (str "-" v))))

