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
  #+cljs (:require-macros [cljs.core.match.macros :refer [match]])
  (:require [clojure.walk :as walk :refer [postwalk]]
            [cemerick.url :as url :refer [url-encode url-decode]]
            #+cljs [cljs.core.match]
            #+clj [clojure.core.match :refer [match]]))

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
  #+clj String
  #+cljs string
  (encode-parameter [s] s)

  #+clj CharSequence
  #+clj (encode-parameter [s] s)

  #+clj Long
  #+cljs number
  (encode-parameter [s] s)

  ;; We do URL encode keywords, however. Namespaced
  ;; keywords use a separated of %2F (a URL encoded forward slash).

  #+clj clojure.lang.Keyword
  #+cljs cljs.core.Keyword
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
  #+clj String
  #+cljs string
  (segment-regex-group [this]
    #+clj (str "\\Q" this "\\E")
    #+cljs (.replace this #"/(\W)/g" "\\$1"))
  (param-key [_] nil)
  (transform-param [_] identity)
  (unmatch-segment [this _] {:path [this]})

  #+clj java.util.regex.Pattern
  #+cljs js/RegExp
  (segment-regex-group [this]
    #+clj (.pattern this)
    #+cljs (str this))
  (param-key [_] nil)
  (transform-param [_] identity)
  (matches? [this s] (re-matches this (str s)))

  #+clj clojure.lang.APersistentVector
  #+cljs cljs.core.PersistentVector
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
      {:path [k]
       :params [[k
                 #(if-let [v (get params k)]
                    (if (matches? (first this) v)
                      (encode-parameter v)
                      (throw (ex-info (str "Parameter value of " v " (key " k ") "
                                           "is not compatible with the route pattern " this)
                                      {})))
                    (throw (ex-info (str "No parameter found in params for key " k)
                                    {})))]]}))

  #+clj clojure.lang.Keyword
  #+cljs cljs.core.Keyword
  ;; This is a very common form, so we're conservative as a defence against injection attacks.
  (segment-regex-group [_] "[A-Za-z0-9\\-\\_\\.]+")
  (param-key [this] this)
  (transform-param [_] identity)
  (unmatch-segment [this params]
    {:path [this]
     :params [[this
               #(if-let [v (this params)]
                  (encode-parameter v)
                  (throw (ex-info (str "Cannot form URI without a value given for "
                                       this " parameter")
                                  {})))]]})

  #+clj clojure.lang.Fn
  #+cljs function
  (segment-regex-group [this]
    (condp = this
     keyword "[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*(?:%2F[A-Za-z]+[A-Za-z0-9\\*\\+\\!\\-\\_\\?\\.]*)?"
     long "-?\\d{1,19}"
     :otherwise (throw (ex-info (str "Unidentified function qualifier to pattern segment: " this) {}))))
  (transform-param [this]
    (condp = this
      ;; keyword is close, but must be applied to a decoded string, to work with namespaced keywords
      keyword (comp keyword url-decode)
      long #+clj #(Long/parseLong %) #+cljs #(js/Number %)
      (throw (ex-info (str "Unrecognized function " this) {}))))
  (matches? [this s]
    (condp = this
      keyword (keyword? s)
      long #+clj (isa? (class s) java.lang.Number) #+cljs (not (js/isNaN (js/Number s))))))

;; A Route is a pair. The pair has two halves: a pattern on the left,
;; while the right contains the result if the pattern matches.

(defprotocol Pattern
  ;; Return truthy if the given pattern matches the given path. By
  ;; truthy, we mean a map containing (at least) the rest of the path to
  ;; match in a :remainder entry
  (match-pattern [_ ^String path])
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
  #+clj String
  #+cljs string
  (match-pattern [this env]
    (match-beginning (str "(" (segment-regex-group this) ")") env))
  (unmatch-pattern [this _] {:path [this]})

  #+clj java.util.regex.Pattern
  #+cljs js/RegExp
  (match-pattern [this env]
    (match-beginning (str "(" (segment-regex-group this) ")") env))

  #+clj Boolean
  #+cljs boolean
  (match-pattern [this env]
    (when this (assoc env :remainder "")))

  #+clj clojure.lang.APersistentVector
  #+cljs cljs.core.PersistentVector
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
    (apply merge-with concat
           (map #(unmatch-segment % (:params m)) this)))

  #+clj clojure.lang.Keyword
  #+cljs cljs.core.Keyword
  (match-pattern [this env] (when (= this (:request-method env)) env))
  (unmatch-pattern [this _] nil)

  #+clj clojure.lang.APersistentMap
  #+cljs cljs.core.PersistentArrayMap
  (match-pattern [this env]
    (when (every? (fn [[k v]]
                    (cond
                     (or (fn? v) (set? v)) (v (get env k))
                     :otherwise (= v (get env k))))
                  (seq this))
      env))
  (unmatch-pattern [_ _] nil))

(defn unmatch-pair [v m]
  (when-let [r (unresolve-handler (second v) m)]
    (merge-with concat (unmatch-pattern (first v) m) r)))

(extend-protocol Matched
  #+clj String
  #+cljs string
  (unresolve-handler [_ _] nil)

  #+clj clojure.lang.APersistentVector
  #+cljs cljs.core.PersistentVector
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #+clj clojure.lang.PersistentList
  #+cljs cljs.core.List
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #+clj clojure.lang.APersistentMap
  #+cljs cljs.core.PersistentArrayMap
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #+clj clojure.lang.LazySeq
  #+cljs cljs.core.LazySeq
  (resolve-handler [this m] (some #(match-pair % m) this))
  (unresolve-handler [this m] (some #(unmatch-pair % m) this))

  #+clj clojure.lang.Symbol
  #+cljs cljs.core.Symbol
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #+clj clojure.lang.Var
  #+clj (resolve-handler [this m] (succeed this m))
  #+clj (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #+clj clojure.lang.Keyword
  #+cljs cljs.core.Keyword
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  #+clj clojure.lang.Fn
  #+cljs function
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) "")))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [route path & {:as options}]
  (-> (match-pair route (merge options {:remainder path :route route}))
      (dissoc :route)))

(defn- path-and-params
  [route handler params]
  (when (nil? handler)
    (throw (ex-info "Cannot form URI from a nil handler" {})))
  (let [{:keys [path params]} (unmatch-pair route {:handler handler :params params})]
    {:path path
     :params (into {} params)}))

(defn route-params
  "Given a route definition data structure and a handler returns a set of the params which
   must be satisfied in order to construct the path to that handler"
  [route handler]
  (set (keys (:params (path-and-params route handler {})))))

(defn path-for
  "Given a route definition data structure, a handler and an option map, return a
  path that would route to the handler. The map must contain the values to any
  parameters required to create the path, and extra values are silently ignored."
  [route handler & {:as params}]
  (let [{:keys [path params]} (path-and-params route handler params)]
    (reduce (fn [url token]
              (str url (if-let [f (get params token)]
                         (f)
                         token))) path)))

;; cljx version of ring.util.codec FormEncodeable protocol
;; Difference is it accepts no custom encoding and uses UTF-8
;; This is required for `path-with-query-for` function
(defprotocol FormEncodeable
  (form-encode [x]))

(extend-protocol FormEncodeable
  #+clj String
  #+cljs string
  (form-encode [unencoded] (url-encode unencoded))

  #+clj clojure.lang.APersistentMap
  #+cljs cljs.core.PersistentArrayMap
  (form-encode [params]
    (letfn [(encode [x] (form-encode x))
            (encode-param [[k v]] (str (encode (name k)) "=" (encode v)))]
      (->> params
           (mapcat
            (fn [[k v]]
              (if (or (seq? v) (sequential? v) )
                (map #(encode-param [k %]) v)
                [(encode-param [k v])])))
           (clojure.string/join "&"))))

  #+clj Object
  #+cljs default
  (form-encode [x] (form-encode (str x))))

(defn path-with-query-for
  "Like path-for, but extra parameters will be appended to the url as query parameters
  rather than silently ignored"
  [route handler & {:as all-params}]
  (let [{:keys [path params]} (path-and-params route handler all-params)
        path (reduce (fn [url token]
                       (str url (if-let [f (get params token)]
                                  (f)
                                  token))) path)
        query-params (not-empty (into (sorted-map) (apply dissoc all-params (keys params))))]
    (apply str path (when query-params
                      ["?" (form-encode query-params)]))))

;; --------------------------------------------------------------------------------
;; 3. Make it fast
;; --------------------------------------------------------------------------------

(defrecord CompiledPrefix [prefix regex]
  Pattern
  (match-pattern [this env]
    (when-let [path (last (re-matches regex (:remainder env)))]
      (assoc env :remainder path)))
  (unmatch-pattern [this env] {:path [prefix]}))

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
