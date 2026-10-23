(ns example.core
  "Reads commits."
  (:require [clojure.string :as str]))

(def ^:const max-commits 500)

(defrecord Commit [hash subject])

(defn parse-line
  "Splits a --oneline line."
  [line]
  (let [[hash & words] (str/split line #"\s+")]
    (->Commit hash (str/join " " words))))

;; Keeps the first commits.
(defn recent [lines]
  (->> lines
       (remove str/blank?)
       (map parse-line)
       (take max-commits)))

(comment (recent ["a1b2c3d Fix it" "" "e4f5a6b Add tests"]) 42 3.5 :key nil true)
