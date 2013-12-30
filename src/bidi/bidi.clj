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

(ns bidi.bidi
  (:require
   [clojure.core.match :refer (match)]
   [clojure.java.io :as io]
   [clojure.walk :refer (postwalk)]
   [ring.util.response :refer (file-response url-response)]
   [ring.middleware.content-type :refer (wrap-content-type)]
   [ring.middleware.file-info :refer (wrap-file-info)])
  (:import
   (clojure.lang PersistentVector Symbol Keyword PersistentArrayMap PersistentHashSet PersistentList Fn LazySeq Var)))

;; --------------------------------------------------------------------------------
;; 1 & 2 Make it work and make it right
;; http://c2.com/cgi/wiki?MakeItWorkMakeItRightMakeItFast
;; --------------------------------------------------------------------------------

;; A PatternSegment is part of a segmented pattern, where the pattern is
;; given as a vector. Each segment can be of a different type, and each
;; segment can optionally be associated with a key, thereby contributing
;; a route parameter.

(defprotocol PatternSegment
  (match-segment [_])
  (param-key [_])
  (unmatch-segment [_ params])
  (matches? [_ s]))

(extend-protocol PatternSegment
  String
  (match-segment [this] (format "\\Q%s\\E" this))
  (unmatch-segment [this _] this)
  (param-key [_] nil)

  java.util.regex.Pattern
  (match-segment [this] (.pattern this))
  (param-key [_] nil)
  (matches? [this s] (re-matches this s))

  Keyword
  ;; By default, a keyword can represent any string.
  (match-segment [_] "(.*)")
  (unmatch-segment [this params]
    (if-let [v (this params)]
      (str v)
      (throw (ex-info (format "Cannot form URI without a value given for %s parameter" this) {}))))
  (param-key [this] this)

  PersistentVector
  ;; A vector allows a keyword to be associated with a segment. The
  ;; segment comes first, then the keyword.
  (match-segment [this] (format "(%s)" (match-segment (first this))))
  (unmatch-segment [this params]
    (let [k (second this)]
      (if-not (keyword? k)
        (throw (ex-info (format "If a PatternSegment is represented by a vector, the second element must be the key associated with the pattern: %s" this) {})))
      (if-let [v (get params k)]
        (if (matches? (first this) v)
          v
          (throw (ex-info (format "Parameter value of %s (key %s) is not compatible with the route pattern %s" v k this) {})))
        (throw (ex-info (format "No parameter found in params for key %s" k) {})))))
  (param-key [this] (let [k (second this)]
                      (if (keyword? k)
                        k
                        (throw (ex-info (format "If a PatternSegment is represented by a vector, the second element must be the key associated with the pattern: %s" this) {}))))))

;; A Route is a pair. The pair has two halves: a pattern on the left,
;; something that is matched by the pattern on the right.

(defprotocol Pattern
  ;; Return truthy if the given pattern matches the given path. By
  ;; truthy, we mean a map containing (at least) the rest of the path to
  ;; match in a :remainder entry
  (match-pattern [_ ^String path])
  (unmatch-pattern [_ m]))

(defprotocol Matched
  (resolve-handler [_ path])
  (unresolve-handler [_ m]))

(defn match-pair
  "A pair contains a pattern to match (either fully or partially) and an
  expression yielding a handler. The second parameter is a map
  containing state, including the remaining path."
  [[pattern matched] match-state]
  (when-let [match-result (match-pattern pattern match-state)]
    (resolve-handler matched (merge match-state match-result))))

(defn match-beginning
  "Match the beginning of the :remainder value in m. If matched, update
  the :remainder value in m with the path that remains after matching."
  [regex-pattern match-state]
  (when-let [path (last (re-matches (re-pattern (str regex-pattern "(.*)"))
                                    (:remainder match-state)))]
    (assoc match-state :remainder path)))

(defn succeed [handler m]
  (when (= (:remainder m) "")
      (merge (dissoc m :remainder) {:handler handler})))

(extend-protocol Pattern
  String
  (match-pattern [this match-state]
    (match-beginning (match-segment this) match-state))
  (unmatch-pattern [this _] this)

  java.util.regex.Pattern
  (match-pattern [this match-state] (match-beginning (match-segment this) match-state))

  Boolean
  (match-pattern [this match-state]
    (when this (assoc match-state :remainder "")))

  PersistentVector
  (match-pattern [this match-state]
    (let [pattern (re-pattern (str (reduce str (map match-segment this)) "(.*)"))]
      (when-let [groups (next (re-matches pattern (:remainder match-state)))]
        (-> match-state
            (assoc-in [:remainder] (last groups))
            (update-in [:params] merge (zipmap (keep param-key this) (butlast groups)))))))
  (unmatch-pattern [this m]
    (apply str (map #(unmatch-segment % (:params m)) this)))

  Keyword
  (match-pattern [this match-state] (when (= this (:request-method match-state)) match-state))
  (unmatch-pattern [_ _] "")

  PersistentArrayMap
  (match-pattern [this match-state]
    (when (every? (fn [[k v]]
                    (cond
                     (or (fn? v) (set? v)) (v (get match-state k)) ;; TODO use ifn? instead
                     :otherwise (= v (get match-state k))))
                  (seq this))
      match-state))
  (unmatch-pattern [_ _] ""))

(defn unmatch-pair [v m]
  (when-let [r (unresolve-handler (second v) m)]
    (str (unmatch-pattern (first v) m) r)))

(extend-protocol Matched
  String
  (unresolve-handler [_ _] nil)

  PersistentVector
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  PersistentList
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  LazySeq
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  Symbol
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  Var
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  Keyword
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  Fn
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) "")))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [path route & {:as options}]
  (->
   (match-pair route (merge options {:remainder path :route route}))
   (dissoc :route)))

(defn path-for
  "Given a route definition data structure and an option map, return a
  path that would route to the handler entry in the map. The map must
  also contain the values to any parameters required to create the path."
  [route handler & {:as params}]
  (unmatch-pair route {:handler handler :params params}))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  [route]
  (fn [{:keys [uri] :as request}]
    (let [{:keys [handler params]} (apply match-route uri route (apply concat (seq request)))]
      (when handler
        (handler (-> request (assoc :route-params params)))))))


;; Any types can be used which satisfy bidi protocols.

;; Here are some built-in ones.

;; Redirect can be matched (appear on the right-hand-side of a route)
;; and returns a handler that can redirect to the given target.
(defrecord Redirect [status target]
  Matched
  (resolve-handler [this m]
    (when (= "" (:remainder m))
      (assoc (dissoc m :remainder)
        :handler (fn [req]
                   {:status status
                    :headers {"Location" (path-for (:route m) target)}
                    :body ""}))))
  (unresolve-handler [this m] nil))

;; Use this to map to paths (e.g. /static) that are expected to resolve
;; to a Java resource, and should fail-fast otherwise (returning a 404).
(defrecord Resources [options]
  Matched
  (resolve-handler [this m]
    (assoc (dissoc m :remainder)
      :handler (if-let [res (io/resource (str (:prefix options) (:remainder m)))]
                 (-> (fn [req] (url-response res))
                     (wrap-file-info (:mime-types options))
                     (wrap-content-type options))
                 {:status 404})))
  (unresolve-handler [this m] nil))

;; Use this to map to resources, will return nil if resource doesn't
;; exist, allowing other routes to be tried. Use this to try the path as
;; a resource, but to continue if not found.
(defrecord ResourcesMaybe [options]
  Matched
  (resolve-handler [this m]
    (when-let [res (io/resource (str (:prefix options) (:remainder m)))]
      (assoc (dissoc m :remainder)
        :handler (-> (fn [req] (url-response res))
                     (wrap-file-info (:mime-types options))
                     (wrap-content-type options)))))
  (unresolve-handler [this m] nil))

;; WrapMiddleware can be matched (appear on the right-hand-side of a route)
;; and returns a handler wrapped in the given middleware.
(defrecord WrapMiddleware [matched middleware]
  Matched
  (resolve-handler [this m]
    (let [r (resolve-handler matched m)]
      (if (:handler r) (update-in r [:handler] middleware) r)))
  (unresolve-handler [this m] (unresolve-handler matched m))) ; pure delegation

;; Alternates can be used as a pattern. It is constructed with a vector
;; of possible matching candidates. If one of the candidates matches,
;; the route is matched. The first pattern in the vector is considered
;; the canonical pattern for the purposes of URI formation with
;; (path-for).
(defrecord Alternates [routes]
  Pattern
  (match-pattern [this m] (some #(match-pattern % m) routes))
  (unmatch-pattern [this m] (unmatch-pattern (first routes) m)))

;; --------------------------------------------------------------------------------
;; 3. Make it fast
;; --------------------------------------------------------------------------------

(defrecord CompiledPrefix [prefix regex]
  Pattern
  (match-pattern [this env]
    (when-let [path (last (re-matches regex (:remainder env)))]
      (assoc env :remainder path)))
  (unmatch-pattern [this env] prefix))

(defn compile-prefix
  "Improve performance by composing the regex pattern ahead of time."
  [s] (->CompiledPrefix s (re-pattern (format "\\Q%s\\E(.*)" s))))

(defn compile-route [route]
  (postwalk
   #(match
     %
     [(s :guard string?) h] [(compile-prefix s) h]
     ;; TODO put other performance optimizations here
     :else %)
   route))
