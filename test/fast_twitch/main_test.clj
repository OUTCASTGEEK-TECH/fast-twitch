(ns fast-twitch.main-test
  (:require [clojure.test :refer [deftest is]]
            [fast-twitch.main :as main]))

(deftest main-test
  (is (= 0 (main/-main))))
