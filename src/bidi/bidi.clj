;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns bidi.bidi)

(defprotocol Matcher
  (match-pair [_ path])
  (match-right [_ path])
  (match-left [_ path]))

(extend-protocol Matcher
  String
  (match-left [s m] (when-let [path (last (re-matches (re-pattern (format "(\\Q%s\\E)(.*)" s)) (:path m)))]
                      {:path path}))

  clojure.lang.PersistentVector
  (match-pair [v m] (when-let [m2 (match-left (first v) m)]
                       (match-right (second v) (merge m m2))))
  (match-right [v m] (first (keep #(match-pair % m) v)))
  (match-left [v m]
    (when-let [groups (rest (re-matches
                             (re-pattern (reduce str (concat (map #(cond (keyword? %) "(.*)" :otherwise %) v) ["(.*)"]))) (:path m)))]
      (-> m
          (update-in [:params] merge (zipmap (filter keyword? v) (butlast groups)))
          (assoc-in [:path] (last groups)))))

  clojure.lang.Symbol
  (match-right [v m] (merge m {:handler v}))

  clojure.lang.Keyword
  (match-right [v m] (merge m {:handler v})))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [routes path]
  (match-pair routes {:path path}))

(defprotocol Unmatcher
  (unmatch-pair [_ m])
  (unmatch-left [_ m])
  (unmatch-right [_ m]))

(extend-protocol Unmatcher
  String
  (unmatch-left [s m] s)
  (unmatch-right [s m] nil)

  clojure.lang.PersistentVector
  (unmatch-left [v m] (apply str
                             (let [replaced (replace (:params m) v)]
                               (if-let [k (first (filter keyword? replaced))]
                                 (throw (ex-info (format "Keyword %s not supplied" k) {:param k}))
                                 replaced))))
  (unmatch-pair [v m] (when-let [r (unmatch-right (second v) m)] (str (unmatch-left (first v) m) r)))
  (unmatch-right [v m] (first (keep #(unmatch-pair % m) v)))

  clojure.lang.Symbol
  (unmatch-right [s m] (when (= s (:handler m)) ""))

  clojure.lang.Keyword
  (unmatch-right [s m] (when (= s (:handler m)) "")))

(defn path-for
  "Given a route definition data structure and an option map, return a
  path that would route to the handler entry in the map. The map must
  also contain the values to any parameters required to create the path."
  [routes & {:as m}]
  (str (unmatch-pair routes m) (:path m)))
