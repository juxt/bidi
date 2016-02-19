;; Copyright © 2014, JUXT LTD.

(ns bidi.server
  (:require
   [bidi.bidi :as bidi :refer :all]
   [bidi.ring :as br]
   [bidi.schema :as bsc]
   [schema.core :as s]
   [schema.coerce :as sc]
   [schema.utils :refer [error?]]))

(s/defschema Server {:scheme (s/enum :http :https)
                     :host s/Str})

(s/defschema ServerWithRoutes
  [(s/one [Server] "Server")
   bsc/RoutePair])

(def multi-server-model
  (sc/coercer
   [ServerWithRoutes]
   {[Server] (fn [x]
               (if-not (s/check Server x) (vector x) x))}))

(defrecord UriModel [servers])

(defn uri-model [& servers-with-routes]
  (let [servers (multi-server-model (vec servers-with-routes))]
    (when (error? servers)
      (throw (ex-info (format "Error in server model: %s"
                              (pr-str (:error servers)))
                      {:error (:error servers)})))
    (map->UriModel {:servers servers})))

(defn uri-for
  [uri-router handler & params]
  (some
   (fn [[servers & routes]]
     (when-let [path (apply path-for ["" (vec routes)] handler params)]
       (let [{:keys [scheme host]} (first servers)]
         (format "%s://%s%s" (name scheme) host path))))
   (:servers uri-router)))

(defn find-handler [uri-model req]
  (let [server {:scheme (:scheme req)
                :host (get-in req [:headers "host"])}]
    (some
     (fn [[servers & routes]]
       (let [routes (vec routes)]
         (when (some (partial = server) servers)
           (->
            (resolve-handler
             routes
             (assoc req
                    :remainder (:uri req)
                    :route ["" routes]
                    :uri-for (partial uri-for uri-model)))
            (dissoc :route)))))
     (:servers uri-model))))

(defn make-handler
  ([uri-model] (make-handler uri-model identity))
  ([uri-model handler-fn]
   (fn [req]
     (let [{:keys [handler route-params] :as match-context}
           (find-handler uri-model req)]
       (when-let [handler (handler-fn handler)]
         (br/request
          handler
          (-> req
              (update-in [:params] merge route-params)
              (update-in [:route-params] merge route-params))
          (apply dissoc match-context :handler (keys req))))))))
