;; Copyright © 2014, JUXT LTD.

(ns bidi.bidi
  (:refer-clojure :exclude [uuid])
  (:require clojure.string)
  #?(:cljs (:import goog.Uri)))

(defn url-encode
  [string]
  (some-> string
          str
          #?(:clj (java.net.URLEncoder/encode "UTF-8")
             :cljs (js/encodeURIComponent))
          (.replace "+" "%20")))

(defn url-decode
  ([string] #?(:clj (url-decode string "UTF-8")
               :cljs (some-> string str (js/decodeURIComponent))))
  #?(:clj ([string encoding]
           (some-> string str (java.net.URLDecoder/decode encoding)))))

(defn uuid
  "Function for creating a UUID of the appropriate type for the platform.
Note that this function should _only_ be used in route patterns as, at least
in the case of ClojureScript, it does not validate that the input string is
actually a valid UUID (this is handled by the route matching logic)."
  [s]
  #?(:clj (java.util.UUID/fromString s)
     :cljs (cljs.core.UUID. s)))

;; When forming paths, parameters are encoded into the URI according to
;; the parameter value type.

(defprotocol ParameterEncoding
  (encode-parameter [_]))

(extend-protocol ParameterEncoding
  ;; We don't URL encode strings, we leave the choice of whether to do so
  ;; to the caller.
  #?(:clj String
     :cljs string)
  (encode-parameter [s] s)

  #?(:clj CharSequence)
  #?(:clj (encode-parameter [s] s))

  #?(:clj Number
     :cljs number)
  (encode-parameter [s] s)

  #?(:clj java.util.UUID
     :cljs cljs.core.UUID)
  (encode-parameter [s] (str s))

  ;; We do URL encode keywords, however. Namespaced
  ;; keywords use a separated of %2F (a URL encoded forward slash).

  #?(:clj clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (encode-parameter [k]
    (url-encode
     (str (namespace k)
          (when (namespace k) "/")
          (name k)))))

;; A PatternSegment is part of a segmented pattern, where the pattern is
;; given as a vector. Each segment can be of a different type, and each
;; segment can optionally be associated with a key, thereby contributing
;; a route parameter.

(defprotocol PatternSegment
  ;; segment-regex-group must return the regex pattern that will consume the
  ;; segment, when matching routes.
  (segment-regex-group [_])
  ;; param-key, if non nil, specifies the key in the parameter map which
  ;; holds the segment's value, returned from matching a route
  (param-key [_])
  ;; transform specifies a function that will be applied the value
  ;; extracted from the URI when matching routes.
  (transform-param [_])
  ;; unmatch-segment generates the part of the URI (a string) represented by
  ;; the segment, when forming URIs.
  (unmatch-segment [_ params])
  ;; matches? is used to check if the type or value of the parameter
  ;; satisfies the segment qualifier when forming a URI.
  (matches? [_ s]))

(extend-protocol PatternSegment
  #?(:clj String
     :cljs string)
  (segment-regex-group [this]
    #?(:clj (str "\\Q" this "\\E")
       :cljs this))
  (param-key [_] nil)
  (transform-param [_] identity)
  (unmatch-segment [this _] this)

  #?(:clj java.util.regex.Pattern
     :cljs js/RegExp)
  (segment-regex-group [this]
    #?(:clj (.pattern this)
       :cljs (aget this "source")))
  (param-key [_] nil)
  (transform-param [_] identity)
  (matches? [this s] (re-matches this (str s)))

  #?(:clj clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  ;; A vector allows a keyword to be associated with a segment. The
  ;; qualifier for the segment comes first, then the keyword.
  ;; The qualifier is usually a regex
  (segment-regex-group [this] (segment-regex-group (first this)))
  (param-key [this]
    (let [k (second this)]
      (if (keyword? k)
        k
        (throw (ex-info (str "If a PatternSegment is represented by a vector, the second
                               element must be the keyword associated with the pattern: "
                             this)
                        {})))))
  (transform-param [this] (transform-param (first this)))
  (unmatch-segment [this params]
    (let [k (second this)]
      (if-not (keyword? k)
        (throw (ex-info (str "If a PatternSegment is represented by a vector, the second element
                               must be the key associated with the pattern: "
                             this)
                        {})))
      (if-let [v (get params k)]
        (if (matches? (first this) v)
          (encode-parameter v)
          (throw (ex-info (str "Parameter value of " v " (key " k ") "
                               "is not compatible with the route pattern " this)
                          {})))
        (throw (ex-info (str "No parameter found in params for key " k)
                        {})))))

  #?(:clj clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  ;; This is a very common form, so we're conservative as a defence against injection attacks.
  (segment-regex-group [_] "[A-Za-z0-9\\-\\_\\.]+")
  (param-key [this] this)
  (transform-param [_] identity)
  (unmatch-segment [this params]
    (if-let [v (this params)]
      (encode-parameter v)
      (throw (ex-info (str "Cannot form URI without a value given for "
                           this " parameter")
                      {}))))

  #?(:clj clojure.lang.Fn
     :cljs function)
  (segment-regex-group [this]
    (condp = this
     keyword "[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*(?:%2F[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*)?"
     long "-?\\d{1,19}"
     uuid "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
     :otherwise (throw (ex-info (str "Unidentified function qualifier to pattern segment: " this) {}))))
  (transform-param [this]
    (condp = this
      ;; keyword is close, but must be applied to a decoded string, to work with namespaced keywords
      keyword (comp keyword url-decode)
      long #?(:clj #(Long/parseLong %) :cljs #(js/Number %))
      uuid uuid
      (throw (ex-info (str "Unrecognized function " this) {}))))
  (matches? [this s]
    (condp = this
      keyword (keyword? s)
      long #?(:clj (some #(instance? % s) [Byte Short Integer Long])
              :cljs (not (js/isNaN s)))
      uuid (instance? #?(:clj java.util.UUID :cljs cljs.core.UUID) s))))

;; A Route is a pair. The pair has two halves: a pattern on the left,
;; while the right contains the result if the pattern matches.

(defprotocol Pattern
  (match-pattern [_ env]
    "Return a new state if this pattern matches the given state, or
    falsy otherwise. If a new state is returned it will usually have the
    rest of the path to match in the :remainder entry.")
  (unmatch-pattern [_ m]))

(defprotocol Matched
  (resolve-handler [_ m])
  (unresolve-handler [_ m]))

(defn just-path
  [path]
  (let [uri-string (str "http://bidi.bidi/" path)]
    (subs #?(:clj (.getPath (java.net.URL. uri-string))
             :cljs (.getPath (goog.Uri. uri-string)))
          1)))

(defn match-pair
  "A pair contains a pattern to match (either fully or partially) and an
  expression yielding a handler. The second parameter is a map
  containing state, including the remaining path."
  [[pattern matched] orig-env]
  (let [env (update orig-env :remainder just-path)]
    (when-let [match-result (match-pattern pattern env)]
      (resolve-handler matched match-result))))

(defn match-beginning
  "Match the beginning of the :remainder value in m. If matched, update
  the :remainder value in m with the path that remains after matching."
  [regex-pattern env]
  (when-let [path (last (re-matches (re-pattern (str regex-pattern "(.*)"))
                                    (:remainder env)))]
    (assoc env :remainder path)))

(defn succeed [handler m]
  (when (= (:remainder m) "")
    (merge (dissoc m :remainder) {:handler handler})))

(extend-protocol Pattern
  #?(:clj String
     :cljs string)
  #?(:clj (match-pattern [this env]
                         (if (= (.length this) 0)
                           env
                           (when (.startsWith ^String (:remainder env) this)
                             (assoc env :remainder (.substring ^String (:remainder env) (.length this))))))
     ;; TODO: Optimize cljs version as above
     :cljs (match-pattern [this env]
                          (match-beginning (str "(" (segment-regex-group this) ")") env)))
  (unmatch-pattern [this _] this)

  #?(:clj java.util.regex.Pattern
     :cljs js/RegExp)
  (match-pattern [this env]
    (match-beginning (str "(" (segment-regex-group this) ")") env))
  ;; We can't unmatch-pattern as you can't go from a regex to a
  ;; string (it's a many-to-one mapping)

  #?(:cljs
     (unmatch-pattern [this m]
                      (let [p (.pattern this)]
                        (unmatch-pattern (clojure.string/replace p #"\\\\" "") m))))

  #?(:clj Boolean
     :cljs boolean)
  (match-pattern [this env]
    (when this (assoc env :remainder "")))
  (unmatch-pattern [this _] (when this ""))

  #?(:clj clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (match-pattern [this env]
    (when-let [groups (as-> this %
                        ;; Make regexes of each segment in the vector
                        (map segment-regex-group %)
                        ;; Form a regexes group from each
                        (map (fn [x] (str "(" x ")")) %)
                        (reduce str %)
                        ;; Add the 'remainder' group
                        (str % "(.*)")
                        (re-pattern %)
                        (re-matches % (:remainder env))
                        (next %))]
      (let [params (->> groups
                        butlast         ; except the 'remainder' group
                        ;; Transform parameter values if necessary
                        (map list) (map apply (map transform-param this))
                        ;; Pair up with the parameter keys
                        (map vector (map param-key this))
                        ;; Only where such keys are specified
                        (filter first)
                        ;; Merge all key/values into a map
                        (into {}))]
        (-> env
            (assoc-in [:remainder] (last groups))
            (update-in [:route-params] merge params)))))

  (unmatch-pattern [this m]
    (apply str (map #(unmatch-segment % (:params m)) this)))

  #?(:clj clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (match-pattern [this env] (when (= this (:request-method env)) env))
  (unmatch-pattern [_ _] "")

  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentArrayMap)
  (match-pattern [this env]
    (when (every? (fn [[k v]]
                    (cond
                      (or (fn? v) (set? v)) (v (get env k))
                      :otherwise (= v (get env k))))
                  (seq this))
      env))
  (unmatch-pattern [_ _] "")

  #?(:cljs cljs.core.PersistentHashMap)
  #?(:cljs
     (match-pattern [this env]
                    (when (every? (fn [[k v]]
                                    (cond
                                      (or (fn? v) (set? v)) (v (get env k))
                                      :otherwise (= v (get env k))))
                                  (seq this))
                      env)))
  #?(:cljs
     (unmatch-pattern [_ _] ""))

  #?(:clj clojure.lang.APersistentSet
     :cljs cljs.core.PersistentHashSet)
  (match-pattern [this s]
    (some #(match-pattern % s)
          ;; We try to match on the longest string first, so that the
          ;; empty string will be matched last, after all other cases
          (sort-by count > this)))
  (unmatch-pattern [this s] (unmatch-pattern (first this) s))

  #?(:cljs cljs.core.PersistentTreeSet)
  #?(:cljs
     (match-pattern [this s]
                    (some #(match-pattern % s)
                          ;; We try to match on the longest string first, so that the
                          ;; empty string will be matched last, after all other cases
                          (sort-by count > this))))
  #?(:cljs
     (unmatch-pattern [this s] (unmatch-pattern (first this) s))))

(defn unmatch-pair [v m]
  (when-let [r (unresolve-handler (second v) m)]
    (str (unmatch-pattern (first v) m) r)))

(extend-protocol Matched
  #?(:clj String
     :cljs string)
  (unresolve-handler [_ _] nil)

  #?(:clj clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #?(:clj clojure.lang.PersistentList
     :cljs cljs.core.List)
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentArrayMap)
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))
  #?(:cljs cljs.core.PersistentHashMap)
  #?(:cljs (resolve-handler [this m] (some #(match-pair % m) this)))
  #?(:cljs (unresolve-handler [this m] (some #(unmatch-pair % m) this)))

  #?(:clj clojure.lang.LazySeq
     :cljs cljs.core.LazySeq)
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #?(:clj clojure.lang.Symbol
     :cljs cljs.core.Symbol)
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #?(:clj clojure.lang.Var
     :cljs cljs.core.Var)
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (unresolve-handler @this m))

  #?(:clj clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #?(:clj clojure.lang.Fn
     :cljs function)
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  nil
  (resolve-handler [this m] nil)
  (unresolve-handler [this m] nil))

(defn match-route*
  [route path options]
  (-> (match-pair route (assoc options :remainder path :route route))
      (dissoc :route)))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [route path & {:as options}]
  (match-route* route path options))

(defn path-for
  "Given a route definition data structure, a handler and an option map, return a
  path that would route to the handler. The map must contain the values to any
  parameters required to create the path, and extra values are silently ignored."
  [route handler & {:as params}]
  (when (nil? handler)
    (throw (ex-info "Cannot form URI from a nil handler" {})))
  (unmatch-pair route {:handler handler :params params}))

;; --------------------------------------------------------------------------------
;; Route seqs
;; --------------------------------------------------------------------------------

(defprotocol Matches
  (matches [_] "A protocol used in the expansion of possible matches that the pattern can match. This is used to gather all possible routes using route-seq below."))

(extend-protocol Matches
  #?(:clj Object
     :cljs default)
  (matches [this] [this])

  #?(:clj clojure.lang.APersistentSet
     :cljs cljs.core.PersistentHashSet)
  (matches [this] this)

  #?(:cljs cljs.core.PersistentTreeSet)
  #?(:cljs (matches [this] this)))

(defrecord Route [handler path])

(defprotocol RouteSeq
  (gather [_ context] "Return a sequence of leaves"))

(defn route-seq
  ([[pattern matched] ctx]
   (mapcat
    identity
    (for [p (matches pattern)]
      (gather matched (update-in ctx [:path] (fnil conj []) p)))))
  ([route]
   (route-seq route {})))

(extend-protocol RouteSeq
  #?(:clj clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (gather [this context] (mapcat #(route-seq % context) this))

  #?(:clj clojure.lang.PersistentList
     :cljs cljs.core.List)
  (gather [this context] (mapcat #(route-seq % context) this))

  #?(:clj clojure.lang.APersistentMap
     :cljs cljs.core.PersistentArrayMap)
  (gather [this context] (mapcat #(route-seq % context) this))

  #?(:cljs cljs.core.PersistentHashMap)
  #?(:cljs (gather [this context] (mapcat #(route-seq % context) this)))

  #?(:clj clojure.lang.LazySeq
     :cljs cljs.core.LazySeq)
  (gather [this context] (mapcat #(route-seq % context) this))

  #?(:clj Object
     :cljs default)
  (gather [this context] [(map->Route (assoc context :handler this))]))


;; --------------------------------------------------------------------------------
;; Protocols
;; --------------------------------------------------------------------------------

;; RouteProvider - this protocol can be satisfied by records that provide
;; or generate bidi routes. The reason for providing this protocol in
;; bidi is to encourage compatibility between record implementations.
(defprotocol RouteProvider
  (routes [_] "Provide a bidi route structure. Returns a vector pair,
  the first element is the pattern, the second element is the matched
  route or routes."))

;; --------------------------------------------------------------------------------
;; Utility records
;; --------------------------------------------------------------------------------

;; Alternates can be used as a pattern. It is constructed with a vector
;; of possible matching candidates. If one of the candidates matches,
;; the route is matched. The first pattern in the vector is considered
;; the canonical pattern for the purposes of URI formation with
;; (path-for).

;; This is deprecated. You should really use the literal set syntax.
(defrecord Alternates [alts]
  Pattern
  (match-pattern [this m]
    (some #(match-pattern % m)
          ;; We try to match on the longest string first, so that the
          ;; empty string will be matched last, after all other cases
          (sort-by count > alts)))
  (unmatch-pattern [this m] (unmatch-pattern (first alts) m))
  Matches
  (matches [_] alts))

(defn alts [& alts]
  (->Alternates alts))

;; If you have multiple routes which match the same handler, but need to
;; label them so that you can form the correct URI, wrap the handler in
;; a TaggedMatch.
(defrecord TaggedMatch [matched tag]
  Matched
  (resolve-handler [this m]
    (resolve-handler matched (assoc m :tag tag)))
  (unresolve-handler [this m]
    (if (and (keyword? (:handler m)) (= tag (:handler m)))
      ""
      (unresolve-handler matched m)))
  RouteSeq
  (gather [this context] [(map->Route (assoc context :handler matched :tag tag))]))

(defn tag [matched tag]
  (->TaggedMatch matched tag))

(defrecord IdentifiableHandler [id handler]
  Matched
  (resolve-handler [this m]
    (resolve-handler handler (assoc m :id id)))
  (unresolve-handler [this m]
    (when id
      (if (= id (:handler m)) ""
          (unresolve-handler handler m)))))

(defn ^:deprecated handler
  ([k handler]
   (->IdentifiableHandler k handler))
  ([handler]
   (->IdentifiableHandler nil handler)))

;; --------------------------------------------------------------------------------
;; Context
;; --------------------------------------------------------------------------------

;; bidi's match-context can be leveraged by Matched wrappers

(defrecord RoutesContext [routes context]
  Matched
  (resolve-handler [_ m]
    (when-let [m (resolve-handler routes m)]
      (merge context m)))

  (unresolve-handler [_ m]
    (unresolve-handler routes m))

  RouteSeq
  (gather [_ context]
    (gather routes context)))

(defn routes-context
  "Wrap a Matched such that a successful match will merge the given
  context with the match-context. The merge is such that where there
  is a conflict, the inner sub-tree overrides the outer container."
  [routes context]
  (->RoutesContext routes context))

;; --------------------------------------------------------------------------------
;; Deprecated functions
;; --------------------------------------------------------------------------------

;; Route compilation was only marginally effective and hard to
;; debug. When bidi matching takes in the order of 30 micro-seconds,
;; this is good enough in relation to the time taken to process the
;; overall request.

(defn ^:deprecated compile-route [route]
  route)
