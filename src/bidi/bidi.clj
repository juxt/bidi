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
  (match-left [s m] (when-let [path (last (re-matches (re-pattern (format "(\\Q%s\\E)(.*)" s)) (:remainder m)))]
                      (merge m {:remainder path})))

  Boolean
  (match-left [b m] (when b m))

  clojure.lang.PersistentVector
  (match-pair [v m]
    (when-let [m2 (match-left (first v) m)]
      (match-right (second v) (merge m m2))))
  (match-right [v m] (first (keep #(match-pair % m) v)))
  (match-left [v m]
    (let [pattern (reduce str (concat (map #(cond (keyword? %) "(.*)" :otherwise %) v) ["(.*)"]))]
      (when-let [groups (next (re-matches (re-pattern pattern) (:remainder m)))]
        (-> m
            (update-in [:params] merge (zipmap (filter keyword? v) (butlast groups)))
            (assoc-in [:remainder] (last groups))))))

  clojure.lang.Symbol
  (match-right [v m]
    (when (= (:remainder m) "")
      (merge (dissoc m :remainder) {:handler v})))

  clojure.lang.Keyword
  (match-right [v m]
    (when (= (:remainder m) "")
      (merge (dissoc m :remainder) {:handler v})))
  (match-left [k m]
    (when (= k (:request-method m)) m))

  clojure.lang.Fn
  (match-right [f m]
    (when (= (:remainder m) "")
      (merge (dissoc m :remainder) {:handler f})))

  clojure.lang.PersistentArrayMap
  (match-left [options m]
    (when (every? (fn [[k v]] (cond
                     (or (fn? v) (set? v)) (v (get m k))
                     :otherwise (= v (get m k))))
                  (seq options))
      m)))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [routes path & {:as options}]
  (match-pair routes (merge options {:remainder path})))

(defprotocol Unmatcher
  (unmatch-pair [_ m])
  (unmatch-left [_ m])
  (unmatch-right [_ m]))

(extend-protocol Unmatcher
  String
  (unmatch-left [s m] s)
  (unmatch-right [s m] nil)

  clojure.lang.PersistentVector
  (unmatch-left [v m]
    (apply str
           (let [replaced (replace (:params m) v)]
             (if-let [k (first (filter keyword? replaced))]
               (throw (ex-info (format "Keyword %s not supplied" k) {:param k}))
               replaced))))
  (unmatch-pair [v m] (when-let [r (unmatch-right (second v) m)] (str (unmatch-left (first v) m) r)))
  (unmatch-right [v m] (first (keep #(unmatch-pair % m) v)))

  clojure.lang.Symbol
  (unmatch-right [s m] (when (= s (:handler m)) ""))

  clojure.lang.Keyword
  (unmatch-right [k m] (when (= k (:handler m)) ""))
  (unmatch-left [k m] "")

  clojure.lang.PersistentArrayMap
  (unmatch-left [this m] "")
  )

(defn path-for
  "Given a route definition data structure and an option map, return a
  path that would route to the handler entry in the map. The map must
  also contain the values to any parameters required to create the path."
  [routes handler & {:as params}]
  (unmatch-pair routes {:handler handler :params params}))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  [routes]
  (fn [{:keys [uri] :as request}]
    (let [{:keys [handler params]} (apply match-route routes uri (apply concat (seq request)))]
      (when handler
        (handler (-> request
                     (assoc :route-params params)
                     (update-in [:params] #(merge params %))))))))
