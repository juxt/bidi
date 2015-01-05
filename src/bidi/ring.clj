;; Copyright © 2014, JUXT LTD.

(ns bidi.ring
  (:require
   [bidi.bidi :as bidi :refer :all]))

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
