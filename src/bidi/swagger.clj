(ns bidi.swagger
  (:require
   [bidi.bidi :refer (Matched)]
   [clojure.data.json :as json]))

(defrecord OperationObject [])

(extend-protocol Matched
  OperationObject
  (resolve-handler [op m]
    (assoc m :swagger/op op))
  (unresolve-handler [op m]
    (when (= (:operationId op) (:handler m)) "")))

(defn swagger-spec [routes]
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

    {:swagger "2.0"
     :paths (into {} (paths routes))}))

(defn json-swagger-spec [routes]
  (json/write-str (swagger-spec routes)))
