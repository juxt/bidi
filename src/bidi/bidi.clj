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
  (match-route [_ path])
  (match-right [_ path])
  (match-left [_ path]))

(extend-protocol Matcher
  String
  (match-left [s m] (when-let [path (last (re-matches (re-pattern (format "(%s)(.*)" s)) (:path m)))]
                      {:path path}))    ; regex quoting

  clojure.lang.PersistentVector
  (match-route [v m] (when-let [m2 (match-left (first v) m)]
                       (match-right (second v) (merge m m2))))
  (match-right [v m] (first (keep #(match-route % m) v)))
  (match-left [v m]
    (when-let [groups (rest (re-matches
                             (re-pattern (reduce str (map #(cond (keyword? %) "(.*)" :otherwise %) v))) (:path m)))]
      (update-in m [:params] merge (zipmap (filter keyword? v) groups))))

  clojure.lang.Symbol
  (match-right [v m] (merge m {:handler v}))

  clojure.lang.Keyword
  (match-right [v m] (merge m {:handler v})))

(defprotocol Unmatcher
  (unmatch-route [_ m])
  (unmatch-left [_ m])
  (unmatch-right [_ m]))

(extend-protocol Unmatcher
  String
  (unmatch-left [s m] s)
  (unmatch-right [s m] nil)

  clojure.lang.PersistentVector
  (unmatch-left [v m] (apply str (replace (:params m) v)))
  (unmatch-route [v m] (when-let [r (unmatch-right (second v) m)] (str (unmatch-left (first v) m) r)))
  (unmatch-right [v m] (first (keep #(unmatch-route % m) v)))

  clojure.lang.Symbol
  (unmatch-right [s m] (when (= s (:handler m)) ""))

  clojure.lang.Keyword
  (unmatch-right [s m] (when (= s (:handler m)) "")))
