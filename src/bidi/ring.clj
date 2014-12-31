;; Copyright © 2014, JUXT LTD.

(ns bidi.ring
  (:require [bidi.bidi :as bidi :refer :all]
            [clojure.java.io :as io]
            [cemerick.url :as url :refer [url-encode url-decode]]
            [ring.util.response :refer (file-response url-response)]
            [ring.middleware.content-type :refer (wrap-content-type)]
            [ring.middleware.file-info :refer (wrap-file-info)]))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  ([route handler-fn]
      (assert route "Cannot create a Ring handler with a nil Route(s) parameter")
      (fn [{:keys [uri path-info] :as request}]
        (let [path (or path-info uri)
              {:keys [handler route-params]} (apply match-route route path (apply concat (seq request)))]
          (when handler
            ((handler-fn handler)
             (-> request
                 (update-in [:params] merge route-params)
                 (update-in [:route-params] merge route-params)))))))
   ([route] (make-handler route identity)))

;; Any types can be used which satisfy bidi protocols.

;; Here are some built-in ones.

;; Redirect can be matched (appear on the right-hand-side of a route)

;; and returns a handler that can redirect to the given target.
(defrecord Redirect [status target]
  bidi/Matched
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

(defn redirect [target]
  (->Redirect 302 target))

(defn redirect-after-post [target]
  (->Redirect 303 target))

;; Use this to map to paths (e.g. /static) that are expected to resolve
;; to a Java resource, and should fail-fast otherwise (returning a 404).
(defrecord Resources [options]
  bidi/Matched
  (resolve-handler [this m]
    (let [path (url-decode (:remainder m))]
      (when (not-empty path)
        (assoc (dissoc m :remainder)
          :handler (if-let [res (io/resource (str (:prefix options) path))]
                     (-> (fn [req] (url-response res))
                         (wrap-file-info (:mime-types options))
                         (wrap-content-type options))
                     (fn [req] {:status 404}))))))
  (unresolve-handler [this m] nil))

(defn resources [options]
  (->Resources options))

;; Use this to map to resources, will return nil if resource doesn't
;; exist, allowing other routes to be tried. Use this to try the path as
;; a resource, but to continue if not found.  Warning: Java considers
;; directories as resources, so this will yield a positive match on
;; directories, including "/", which will prevent subsequent patterns
;; being tried. The workaround is to be more specific in your
;; patterns. For example, use /js and /css rather than just /. This
;; problem does not affect Files (below).
(defrecord ResourcesMaybe [options]
  bidi/Matched
  (resolve-handler [this m]
    (let [path (url-decode (:remainder m))]
      (when (not-empty path)
        (when-let [res (io/resource (str (:prefix options) path))]
          (assoc (dissoc m :remainder)
            :handler (-> (fn [req] (url-response res))
                         (wrap-file-info (:mime-types options))
                         (wrap-content-type options)))))))
  (unresolve-handler [this m] nil))

(defn resources-maybe [options]
  (->ResourcesMaybe options))

;; Use this to map to files, using file-response. Options sbould include
;; :dir, the root directory containing the files.
(defrecord Files [options]
  bidi/Matched
  (resolve-handler [this m]
    (assoc (dissoc m :remainder)
      :handler (-> (fn [req] (file-response (url-decode (:remainder m))
                                           {:root (:dir options)}))
                   (wrap-file-info (:mime-types options))
                   (wrap-content-type options))))
  (unresolve-handler [this m] nil))

(defn files [options]
  (->Files options))

;; WrapMiddleware can be matched (appear on the right-hand-side of a route)
;; and returns a handler wrapped in the given middleware.
(defrecord WrapMiddleware [matched middleware]
  bidi/Matched
  (resolve-handler [this m]
    (let [r (resolve-handler matched m)]
      (if (:handler r) (update-in r [:handler] middleware) r)))
  (unresolve-handler [this m] (unresolve-handler matched m))) ; pure delegation

(defn wrap-middleware [matched middleware]
  (->WrapMiddleware matched middleware))

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
  bidi/Matched
  (resolve-handler [this m]
    (resolve-handler delegate m))
  (unresolve-handler [this m]
    (if (keyword? (:handler m))
      (when (= name (:handler m)) "")
      (unresolve-handler delegate m))))
