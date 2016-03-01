;; Copyright © 2014, JUXT LTD.

(ns bidi.vhosts
  (:require
   [bidi.bidi :as bidi :refer :all]
   [bidi.ring :as br]
   [bidi.schema :as bsc]
   [schema.core :as s]
   [schema.coerce :as sc]
   [schema.utils :refer [error?]]))

(s/defschema VHost {:scheme (s/enum :http :https)
                     :host s/Str})

(s/defschema VHostWithRoutes
  [(s/one [VHost] "Virtual host")
   bsc/RoutePair])

(def coerce-to-vhosts-model
  (sc/coercer
   [VHostWithRoutes]
   {[VHost] (fn [x]
               (if-not (s/check VHost x) (vector x) x))}))

(defrecord VHostsModel [vhosts])

(defn vhosts-model [& vhosts-with-routes]
  (let [vhosts (coerce-to-vhosts-model (vec vhosts-with-routes))]
    (when (error? vhosts)
      (throw (ex-info (format "Error in server model: %s"
                              (pr-str (:error vhosts)))
                      {:error (:error vhosts)})))
    (map->VHostsModel {:vhosts vhosts})))

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
  [vhosts-model handler & [{:keys [vhost route-params query-params] :as options}]]
  (some
   (fn [[vhosts & routes]]
     (when-let [path (apply path-for ["" (vec routes)] handler (mapcat identity route-params))]
       
       (let [path (if query-params
                    (str path "?" (query-string query-params))
                    path)
             canonical (if vhost
                         (first (filter (comp (partial = (:scheme vhost)) :scheme) vhosts))
                         (first vhosts))
             {:keys [scheme host]} canonical
             uri (format "%s://%s%s" (name scheme) host path)
             relative? (= vhost canonical)]
         {:uri uri
          :path path
          :host host
          :scheme scheme
          :href (if relative? path uri)})))
   (:vhosts vhosts-model)))

(defn find-handler [vhosts-model req]
  (let [vhost {:scheme (:scheme req)
               :host (get-in req [:headers "host"])}]
    (some
     (fn [[vhosts & routes]]
       (let [routes (vec routes)]
         (when (some (partial = vhost) vhosts)
           (->
            (resolve-handler
             routes
             (assoc req
                    :remainder (:uri req)
                    :route ["" routes]
                    :uri-for (fn [handler & [options]]
                               (uri-for vhosts-model handler (merge {:vhost vhost} options)))))
            (dissoc :route)))))
     (:vhosts vhosts-model))))

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

(defrecord Redirect [status target query-params]
  bidi/Matched
  (resolve-handler [this m]
    (when (= "" (:remainder m))
      (cond-> m
        true (assoc :handler this)
        (not (string? target))
        (assoc :location
               (:uri ((:uri-for m) target
                      (merge
                       {:route-params (:route-params m)}
                       (when query-params {:query-params query-params})))))
        true (dissoc :remainder))))
  (unresolve-handler [this m]
    (when (= this (:handler m)) ""))
  br/Ring
  (request [f req m]
    (if-let [location (if-not (string? target) (:location m) target)]
      {:status status
       :headers {"location" location}
       :body (str "Redirect to " location)}
      {:status 500
       :body "Failed to determine redirect location"})))

(defn redirect [target & [opts]]
  (map->Redirect (merge {:status 302 :target target} opts)))


