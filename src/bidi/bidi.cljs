; Copyright © 2013, JUXT LTD. All Rights Reserved.
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
   [clojure.walk :refer (postwalk)]))

(enable-console-print!)

;; --------------------------------------------------------------------------------
;; 1 & 2 Make it work and make it right
;; http://c2.com/cgi/wiki?MakeItWorkMakeItRightMakeItFast
;; --------------------------------------------------------------------------------

;; When forming paths, parameters are encoded into the URI according to
;; the parameter value type.

(defprotocol ParameterEncoding
  (encode-parameter [_]))

(extend-protocol ParameterEncoding
  ;; We don't URL encode strings, we leave the choice of whether to do so
  ;; to the caller.
  string
  (encode-parameter [s] s)

  number
  (encode-parameter [s] s)

  ;; We do URL encode keywords, however. Namespaced
  ;; keywords use a separated of %2F (a URL encoded forward slash).

  Keyword
  (encode-parameter [k]
    (URLEncoder/encode
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
  string
  (segment-regex-group [this] this) ; TODO Is there a JS equiv of //Q //E ?
  (param-key [_] nil)
  (transform-param [_] identity)
  (unmatch-segment [this _] this)

  js/RegExp
  (segment-regex-group [this] (.pattern this))
  (param-key [_] nil)
  (transform-param [_] identity)
  (matches? [this s] (re-matches this (str s)))

  PersistentVector
  ;; A vector allows a keyword to be associated with a segment. The
  ;; qualifier for the segment comes first, then the keyword. The qualifier is usually a regex
  (segment-regex-group [this] (segment-regex-group (first this)))
  (param-key [this] (let [k (second this)]
                      (if (keyword? k)
                        k
                        (throw (ex-info (str "If a PatternSegment is represented by a vector, the second element must be the keyword associated with the pattern: " this) {})))))
  (transform-param [[f _]]
    (if (fn? f)
      (condp = f
        ;; keyword is close, but must be applied to a decoded string, to work with namespaced keywords
        keyword (comp keyword #(js/decodeURIComponent %))
        (throw (ex-info (str "Unrecognized function " f) {})))
      identity))

  (unmatch-segment [this params]
    (let [k (second this)]
      (if-not (keyword? k)
        (throw (ex-info (str "If a PatternSegment is represented by a vector, the second element must be the key associated with the pattern: " this) {})))
      (if-let [v (get params k)]
        (if (matches? (first this) v)
          (encode-parameter v)
          (throw (ex-info (str "Parameter value of " v " (key " k ") is not compatible with the route pattern " this) {})))
        (throw (ex-info (str "No parameter found in params for key " k) {})))))

  Keyword
  ;; This is a very common form, so we're conservative as a defence against injection attacks.
  (segment-regex-group [_] "[A-Za-z0-9\\-\\_\\.]+")
  (param-key [this] this)
  (transform-param [_] identity)
  (unmatch-segment [this params]
    (if-let [v (this params)]
      (encode-parameter v)
      (throw (ex-info (str "Cannot form URI without a value given for " this " parameter ") {}))))

  #_IFn
  #_(segment-regex-group [this]
    (cond
     (= this keyword) "[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*(?:%2F[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*)?"
     :otherwise (throw (ex-info (str "Unidentified function qualifier to pattern segment: " this) {}))))
  #_(matches? [this s]
    (when (= this keyword) (keyword? s))))

;; A Route is a pair. The pair has two halves: a pattern on the left,
;; while the right contains the result if the pattern matches.

(defprotocol Pattern
  ;; Return truthy if the given pattern matches the given path. By
  ;; truthy, we mean a map containing (at least) the rest of the path to
  ;; match in a :remainder entry
  (match-pattern [_ path])
  (unmatch-pattern [_ m]))

(defprotocol Matched
  (resolve-handler [_ m])
  (unresolve-handler [_ m]))

(defn match-pair
  "A pair contains a pattern to match (either fully or partially) and an
  expression yielding a handler. The second parameter is a map
  containing state, including the remaining path."
  [[pattern matched] env]
  (when-let [match-result (match-pattern pattern env)]
    (resolve-handler matched (merge env match-result))))

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

  string
  (match-pattern [this env]
    (match-beginning (str "(" (segment-regex-group this) ")") env))
  (unmatch-pattern [this _] this)

  js/RegExp
  (match-pattern [this env] (match-beginning (str "(" (segment-regex-group this) ")") env))

  boolean
  (match-pattern [this env]
    (when this (assoc env :remainder "")))

  PersistentVector
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
                        butlast       ; except the 'remainder' group
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

  Keyword
  (match-pattern [this env] (when (= this (:request-method env)) env))
  (unmatch-pattern [_ _] "")

  PersistentArrayMap
  (match-pattern [this env]
    (when (every? (fn [[k v]]
                    (cond
                     (or (fn? v) (set? v)) (v (get env k))
                     :otherwise (= v (get env k))))
                  (seq this))
      env))
  (unmatch-pattern [_ _] ""))

(defn unmatch-pair [v m]
  (when-let [r (unresolve-handler (second v) m)]
    (str (unmatch-pattern (first v) m) r)))

(extend-protocol Matched
  string
  (unresolve-handler [_ _] nil)

  PersistentVector
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  PersistentArrayMap
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  PersistentHashMap
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  LazySeq
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  symbol
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #_var
  #_(resolve-handler [this m] (succeed this m))
  #_(unresolve-handler [this m] (when (= this (:handler m)) ""))

  Keyword
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #_Fn
  #_(resolve-handler [this m] (succeed this m))
  #_(unresolve-handler [this m] (when (= this (:handler m)) "")))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [route path & {:as options}]
  (->
     (match-pair route (merge options {:remainder path :route route}))
   (dissoc :route)))

(defn path-for
  "Given a route definition data structure and an option map, return a
  path that would route to the handler entry in the map. The map must
  also contain the values to any parameters required to create the path."
  [route handler & {:as params}]
  (when (nil? handler)
    (throw (ex-info "Cannot form URI from a nil handler" {})))
  (unmatch-pair route {:handler handler :params params}))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  [route]
  (assert route "Cannot create a Ring handler with a nil Route(s) parameter")
  (fn [{:keys [uri path-info] :as request}]
    (let [path (or path-info uri)
          {:keys [handler route-params]} (apply match-route route path (apply concat (seq request)))]
      (when handler
        (handler
         (-> request
             (update-in [:params] merge route-params)
             (update-in [:route-params] merge route-params)))))))

;; Any types can be used which satisfy bidi protocols.

;; Here are some built-in ones.

;; Redirect can be matched (appear on the right-hand-side of a route)

;; and returns a handler that can redirect to the given target.
(defrecord Redirect [status target]
  Matched
  (resolve-handler [this m]
    (when (= "" (:remainder m))
      (assoc (dissoc m :remainder)
        :handler
        (fn [req]
          (let [location (apply path-for (:route m) target
                                (apply concat (seq (:route-params m))))]
            {:status status
             :headers {"Location" location}
             :body (str "Redirect to " location)})))))
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
;; a resource, but to continue if not found.  Warning: Java considers
;; directories as resources, so this will yield a positive match on
;; directories, including "/", which will prevent subsequent patterns
;; being tried. The workaround is to be more specific in your
;; patterns. For example, use /js and /css rather than just /. This
;; problem does not affect Files (below).
(defrecord ResourcesMaybe [options]
  Matched
  (resolve-handler [this m]
    (when-let [res (io/resource (str (:prefix options) (:remainder m)))]
      (assoc (dissoc m :remainder)
        :handler (-> (fn [req] (url-response res))
                     (wrap-file-info (:mime-types options))
                     (wrap-content-type options)))))
  (unresolve-handler [this m] nil))

;; Use this to map to files, using file-response. Options sbould include
;; :dir, the root directory containing the files.
(defrecord Files [options]
  Matched
  (resolve-handler [this m]
    (assoc (dissoc m :remainder)
      :handler (-> (fn [req] (file-response (:remainder m) {:root (:dir options)}))
                   (wrap-file-info (:mime-types options))
                   (wrap-content-type options))))
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

;; If you have multiple routes which match the same handler, but need to
;; label them so that you can form the correct URI, wrap the handler in
;; a TaggedMatch.
(defrecord TaggedMatch [name delegate]
  Matched
  (resolve-handler [this m]
    (resolve-handler delegate m))
  (unresolve-handler [this m]
    (if (keyword? (:handler m))
      (when (= name (:handler m)) "")
      (unresolve-handler delegate m))))

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
  [s] (->CompiledPrefix s (re-pattern (str "\\Q" s "\\E(.*)"))))

(defn compile-route [route]
  (postwalk
   #(match
     %
     [(s :guard string?) h] [(compile-prefix s) h]
     ;; TODO put other performance optimizations here
     :else %)
   route))
