(ns fast-twitch.codecs.sse
  (:require [malli.experimental :as mx]
            [clojure.string :as str]
            [fast-twitch.util.validation :as v]
            [fast-twitch.util.contracts :as contracts])
  (:refer-global :only [TextEncoder Number]))

(mx/defn ^:dynamic encode
  [value :- contracts/SSEEventKeys]
  (let [{:keys! [data] :keys [event id retry]} value]
    (str (when event (str "event: " event "\n"))
         (when (some? id) (str "id: " id "\n"))
         (when (some? retry) (str "retry: " retry "\n"))
         (apply str (map #(str "data: " % "\n") (str/split data #"\r\n|\r|\n" -1)))
         "\n")))

(defn parser
  [options]
  (let [{:keys [max-line-bytes max-event-bytes]
         :or {max-line-bytes 65536 max-event-bytes 1048576}}
          options]
    (atom {:line ""
           :line-bytes 0
           :high-surrogate? false
           :skip-lf? false
           :first? true
           :data []
           :event ""
           :id ""
           :retry nil
           :size 0
           :max-line-bytes max-line-bytes
           :max-event-bytes max-event-bytes})))

(defn- line!
  [state line]
  (if (= line "")
    (let [out (when (seq (:data state))
                (cond-> {:data (str/join "\n" (:data state))
                         :event (if (empty? (:event state)) "message" (:event state))
                         :id (:id state)}
                  (some? (:retry state)) (assoc :retry (:retry state))))]
      [(assoc state
         :data []
         :event ""
         :size 0) out])
    (if (str/starts-with? line ":")
      [state nil]
      (let [index (.indexOf line ":")
            field (if (neg? index) line (subs line 0 index))
            value (if (neg? index) "" (subs line (inc index)))
            value (if (str/starts-with? value " ") (subs value 1) value)]
        [(case field
           "data"
             (let [size (+ (:size state) 1 (.-byteLength (.encode (TextEncoder.) value)))]
               (when (> size (:max-event-bytes state))
                 (v/fail! :fast-twitch.stream/limit-exceeded
                          :sse-parser
                          []
                          (:max-event-bytes state)))
               (-> state
                   (update :data conj value)
                   (assoc :size size)))
           "event" (assoc state :event value)
           "id" (if (str/includes? value "\u0000") state (assoc state :id value))
           "retry" (if (re-matches #"[0-9]+" value)
                     (let [n (Number value)]
                       (if (Number.isSafeInteger n) (assoc state :retry n) state))
                     state)
           state) nil]))))

(defn events!
  "Decodes records on demand so consumption can await each event before parsing more."
  [parser text]
  (letfn
    [(step [start initial]
       (lazy-seq
         (loop [index start
                state initial]
           (if (= index (count text))
             (do (reset! parser state) nil)
             (let [char (subs text index (inc index))
                   first? (:first? state)
                   state (assoc state :first? false)]
               (cond (and first? (= char "\ufeff")) (recur (inc index) state)
                     (and (:skip-lf? state) (= char "\n"))
                       (recur (inc index) (assoc state :skip-lf? false))
                     (#{"\r" "\n"} char)
                       (let [[next event] (line! state (:line state))
                             next (assoc next
                                    :line ""
                                    :line-bytes 0
                                    :high-surrogate? false
                                    :skip-lf? (= char "\r"))]
                         (if event
                           (do (reset! parser next)
                               (cons event (step (inc index) next)))
                           (recur (inc index) next)))
                     :else (let [code (.charCodeAt char 0)
                                 high? (<= 0xd800 code 0xdbff)
                                 low? (<= 0xdc00 code 0xdfff)
                                 bytes (+ (:line-bytes state)
                                          (cond (and low? (:high-surrogate? state)) 1
                                                (< code 0x80) 1
                                                (< code 0x800) 2
                                                :else 3))]
                             (when (> bytes (:max-line-bytes state))
                               (v/fail! :fast-twitch.stream/limit-exceeded
                                        :sse-parser
                                        [:line]
                                        (:max-line-bytes state)))
                             (recur (inc index)
                                    (assoc state
                                      :line (str (:line state) char)
                                      :line-bytes bytes
                                      :high-surrogate? high?
                                      :skip-lf? false)
                             ))))))))]
    (lazy-seq (step 0 @parser))))

(set! encode (contracts/instrument 'fast-twitch.codecs.sse/encode encode))
