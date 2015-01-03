;; Copyright © 2014 JUXT LTD.

(ns bidi.swagger
  (:require
   [bidi.bidi :as bidi :refer (Matched)]))

(defrecord SwaggerOperation [])

(extend-protocol bidi/Matched
  SwaggerOperation
  (resolve-handler [res m]
    (bidi/succeed res m))
  (unresolve-handler [res m]
    (when (= (:operationId res) (:handler m)) "")))

(defn swagger-paths [routes]
  (letfn [(encode-segment [segment]
            (cond
              (keyword? segment)
              (str "{" (name segment) "}")
              :otherwise segment))
          (encode [pattern]
            (cond (vector? pattern)
                  (apply str (map encode-segment pattern))
                  :otherwise pattern))
          (paths
            ([prefix route]
             (let [[pattern matched] route]
               (let [pattern (str prefix (encode pattern))]
                 (cond (vector? matched)
                       (apply concat
                              (for [route matched]
                                (paths pattern route)))
                       :otherwise [pattern matched]))))
            ([route]
             (map vec (partition 2 (paths nil route)))))]
    (into {} (paths routes))))
