;; Copyright © 2014, JUXT LTD.

(ns bidi.vhosts
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

(defrecord VHostsModel [servers])

(defn vhosts-model [& servers-with-routes]
  (let [servers (multi-server-model (vec servers-with-routes))]
    (when (error? servers)
      (throw (ex-info (format "Error in server model: %s"
                              (pr-str (:error servers)))
                      {:error (:error servers)})))
    (map->VHostsModel {:servers servers})))

(defn- query-string [query-params]
  (let [enc (fn [a b] (str a "=" (java.net.URLEncoder/encode b)))
        join (fn [v] (apply str (interpose "&" v)))]
    (join
     (map (fn [[k v]]
            (if (sequential? v)
              (join (map enc (repeat k) v))
              (enc k v)))
          query-params))))

(defn uri-for
  "Return URI info as a map."
  [vhosts-model handler & [{:keys [vhost path-params query-params] :as options}]]
  (some
   (fn [[servers & routes]]
     (when-let [path (apply path-for ["" (vec routes)] handler (mapcat identity path-params))]
       
       (let [path (if query-params
                    (str path "?" (query-string query-params))
                    path)
             canonical (if vhost
                         (first (filter (comp (partial = (:scheme vhost)) :scheme) servers))
                         (first servers))
             {:keys [scheme host]} canonical
             uri (format "%s://%s%s" (name scheme) host path)
             relative? (= vhost canonical)]
         {:uri uri
          :path path
          :host host
          :scheme scheme
          :href (if relative? path uri)})))
   (:servers vhosts-model)))

(defn find-handler [vhosts-model req]
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
                    :uri-for (partial uri-for vhosts-model server)))
            (dissoc :route)))))
     (:servers vhosts-model))))

(defn make-handler
  ([vhosts-model] (make-handler vhosts-model identity))
  ([vhosts-model handler-fn]
   (fn [req]
     (let [{:keys [handler route-params] :as match-context}
           (find-handler vhosts-model req)]
       (when-let [handler (handler-fn handler)]
         (br/request
          handler
          (-> req
              (update-in [:params] merge route-params)
              (update-in [:route-params] merge route-params))
          (apply dissoc match-context :handler (keys req))))))))

(defprotocol ServerModel
  (server-model [_] "Provide a server model, for example: [[{:scheme :http :host \"example.org:8000\"}] routes...]. This allows for greater modularity where servers can be defined individually a composed into a complete UriModel"))

