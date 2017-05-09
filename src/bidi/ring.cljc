;; Copyright © 2014, JUXT LTD.

(ns bidi.ring
  #?(:clj
     (:require
      [bidi.bidi :as bidi :refer :all]
      [clojure.java.io :as io]
      [ring.util.response :refer (file-response url-response resource-response)]
      [ring.middleware.content-type :refer (wrap-content-type)]
      [ring.middleware.not-modified :refer (wrap-not-modified)]
      [ring.middleware.resource :refer (wrap-resource)])
     :cljs
     (:require
      [bidi.bidi :as bidi :refer (match-route*)])))

(defprotocol Ring
  (request [_ req match-context]
    "Handle a Ring request, but optionally utilize any context that was
    collected in the process of matching the handler."))

(extend-protocol Ring
  #?(:clj clojure.lang.Fn
     :cljs function)
  (request [f req _]
    (f req))

  #?(:clj clojure.lang.Var
     :cljs cljs.core.Var)
  (request [v req mc]
    (request (deref v) req mc)))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  ([route handler-fn]
   (assert route "Cannot create a Ring handler with a nil Route(s) parameter")
   (fn [{:keys [uri path-info] :as req}]
     (let [path (or path-info uri)
           {:keys [handler route-params] :as match-context}
           (match-route* route path req)]
       (when handler
         (request
          (handler-fn handler)
          (-> req
              (update-in [:params] merge route-params)
              (update-in [:route-params] merge route-params))
          (apply dissoc match-context :handler (keys req)))))))
  ([route] (make-handler route identity)))

;; Any types can be used which satisfy bidi protocols.

;; Here are some built-in ones.

;; Redirect can be matched (appear on the right-hand-side of a route)
;; and returns a handler that can redirect to the given target.
#?(:clj
   (defrecord Redirect [status target]
     bidi/Matched
     (resolve-handler [this m]
       (when (= "" (:remainder m))
         (cond-> m
           true (assoc :handler this)
           (not (string? target))
           (assoc :location
                  (apply path-for (:route m) target
                         (apply concat (seq (:route-params m)))))
           true (dissoc :remainder))))
     (unresolve-handler [this m]
       (when (= this (:handler m)) ""))
     Ring
     (request [f req m]
       (if-let [location (if-not (string? target) (:location m) target)]
         {:status  status
          :headers {"Location" location}
          :body    (str "Redirect to " location)}
         {:status 500
          :body   "Failed to determine redirect location"}))))

#?(:clj
   (defn redirect [target]
     (->Redirect 302 target)))

#?(:clj
   (defn redirect-after-post [target]
     (->Redirect 303 target)))

;; Use this to map to paths (e.g. /static) that are expected to resolve
;; to a Java resource, and should fail-fast otherwise (returning a 404).
#?(:clj
   (defrecord Resources [options]
     bidi/Matched
     (resolve-handler [this m]
       (let [path (url-decode (:remainder m))]
         (when (not-empty path)
           (assoc (dissoc m :remainder)
                  :handler
                  (-> (fn [req]
                        (if-let [res (resource-response (str (:prefix options) path))]
                          res
                          {:status 404}))
                      (wrap-content-type options))))))
     (unresolve-handler [this m]
       (when (= this (:handler m)) ""))))

#?(:clj
   (defn resources [options]
     (->Resources options)))

;; Use this to map to resources, will return nil if resource doesn't
;; exist, allowing other routes to be tried. Use this to try the path as
;; a resource, but to continue if not found.  Warning: Java considers
;; directories as resources, so this will yield a positive match on
;; directories, including "/", which will prevent subsequent patterns
;; being tried. The workaround is to be more specific in your
;; patterns. For example, use /js and /css rather than just /. This
;; problem does not affect Files (below).
#?(:clj
   (defrecord ResourcesMaybe [options]
     bidi/Matched
     (resolve-handler [this m]
       (let [path (url-decode (:remainder m))]
         (when (not-empty path)
           (when-let [res (io/resource (str (:prefix options) path))]
             (assoc (dissoc m :remainder)
                    :handler (->
                              (fn [req] (resource-response (str (:prefix options) path)))
                              (wrap-content-type options)))))))
     (unresolve-handler [this m]
       (when (= this (:handler m)) ""))))

#?(:clj
   (defn resources-maybe [options]
     (->ResourcesMaybe options)))

;; Use this to map to files, using file-response. Options sbould include
;; :dir, the root directory containing the files.
#?(:clj
   (defrecord Files [options]
     bidi/Matched
     (resolve-handler [this m]
       (assoc (dissoc m :remainder)
              :handler (->
                        (fn [req] (file-response (url-decode (:remainder m))
                                                {:root (:dir options)}))
                        (wrap-content-type options)
                        (wrap-not-modified))))
     (unresolve-handler [this m]
       (when (= this (:handler m)) ""))))

#?(:clj
   (defn files [options]
     (->Files options)))

;; Use this to route to an existing archive.
;; :archive should be a resource
;; :resource-prefix (defaults to /) says where in the archive the content is
#?(:clj
   (defrecord Archive [options]
     bidi/Matched
     (resolve-handler [this m]
       (let [path (url-decode (:remainder m))]
         (when (not-empty path)
           (-> m
               (assoc
                :handler
                (->
                 (fn [req]
                   (url-response (java.net.URL.
                                  (str "jar:" (:archive options) "!"
                                       (or (:resource-prefix options) "/") path))))
                 (wrap-content-type)
                 (wrap-not-modified)))
               (dissoc :remainder)))))
     (unresolve-handler [this m]
       (when (= this (:handler m)) ""))))

#?(:clj
   (defn archive [options]
     (->Archive options)))

;; WrapMiddleware can be matched (appear on the right-hand-side of a route)
;; and returns a handler wrapped in the given middleware.
#?(:clj
   (defrecord WrapMiddleware [matched middleware]
     bidi/Matched
     (resolve-handler [this m]
       (let [r (resolve-handler matched m)]
         (if (:handler r) (update-in r [:handler] middleware) r)))
     (unresolve-handler [this m] (unresolve-handler matched m)))) ; pure delegation

#?(:clj
   (defn wrap-middleware [matched middleware]
     (->WrapMiddleware matched middleware)))
