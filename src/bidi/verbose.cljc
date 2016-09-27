;; Copyright © 2014, JUXT LTD.

(ns bidi.verbose)

(defn leaf [fragment name]
  [fragment name])

(defn branch [fragment & children]
  (let [[[tag param]] children]
    (if (= tag :bidi/param)
      [[fragment param] (vec (rest children))]
      [fragment (vec children)])))

(defn param [name]
  [:bidi/param name])
