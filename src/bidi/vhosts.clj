;; Copyright © 2014, JUXT LTD.

(ns bidi.vhosts
  (:require
   [bidi.bidi :as bidi :refer :all]
   [bidi.ring :as br]
   [bidi.schema :as bsc]
   [schema.core :as s]
   [schema.coerce :as sc]
   [schema.utils :refer [error?]])
  (:import [java.net URL URI]))

(s/defschema VHost {:scheme (s/enum :http :https)
                    :host s/Str})

(s/defschema VHostWithRoutes
  (s/constrained [(s/one [VHost] "Virtual host")
                  bsc/RoutePair] (comp not-empty first) "Must have at least one vhost"))

(defn uri->host [uri]
  (cond-> (.getHost uri)
    (pos? (.getPort uri))
    (str ":" (.getPort uri))))

(def coerce-to-vhost
  (sc/coercer
   VHost
   {VHost (fn [x]
            (cond (instance? URI x)
                  {:scheme (keyword (.getScheme x))
                   :host (uri->host x)}
                  (instance? URL x) (recur x)
                  (string? x) (recur (URI. x))
                  :otherwise x))}))

(def coerce-to-vhosts-model
  (sc/coercer
   [VHostWithRoutes]
   {VHost coerce-to-vhost
    [VHost] (fn [x]
              (if
                  (or (string? x)
                      (instance? URI x)
                      (instance? URL x)) [(coerce-to-vhost x)]
                  (if-not (s/check VHost x) (vector x) x)))}))

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

(defn- segments [s]
  (let [l (re-seq #"[^/]*/?" s)]
    (if (.endsWith s "/") l (butlast l))))

(defn relativize [from to]
  (if (and from to)
    (loop [from (segments from)
           to (segments to)]
      (if-not (= (first from) (first to))
        (str (apply str (repeat (+ (dec (count from))) "../"))
             (apply str to))
        (if (next from)
          (recur (next from) (next to))
          (first to))))
    to))

(defn prioritize-vhosts [vhosts-model vhost]
  (cond->> (:vhosts vhosts-model)
    vhost (sort-by (fn [[vhosts & _]] (if (first (filter (fn [x] (= x vhost)) vhosts)) -1 1)))))

(defn uri-for
  "Return URI info as a map."
  [prioritized-vhosts handler & [{:keys [request vhost route-params query-params prefer fragment] :or {prefer :local} :as options}]]
  (some
   (fn [[vhosts & routes]]

     (when-let [path (apply path-for ["" (vec routes)] handler (mapcat identity route-params))]

       (let [qs (when query-params
                  (query-string query-params))]

         (let [to-vhost (case prefer
                          :local (or (first (filter (partial = vhost) vhosts))
                                     (first vhosts))
                          :first (first vhosts)
                          :same-scheme (first (filter #(= (:scheme vhost) (:scheme %)) vhosts))
                          :http (first (filter #(= :http (:scheme %)) vhosts))
                          :https (first (filter #(= :https (:scheme %)) vhosts))
                          :local-then-same-scheme (or (first (filter (partial = vhost) vhosts))
                                                      (first (filter #(= (:scheme vhost) (:scheme %)) vhosts))
                                                      (first vhosts)))
               uri (format "%s://%s%s%s%s"
                           (name (:scheme to-vhost))
                           (:host to-vhost)
                           path
                           (if qs (str "?" qs) "")
                           (if fragment (str "#" fragment) ""))]
           (merge {:uri uri
                   :path path
                   :host (:host to-vhost)
                   :scheme (:scheme to-vhost)
                   :href (if (and (= vhost to-vhost) request)
                           (relativize (:uri request) path)
                           uri)}
                  (when qs {:query-string qs})
                  (when fragment {:fragment fragment}))))))

   prioritized-vhosts))

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
                               (uri-for (prioritize-vhosts vhosts-model vhost) handler (merge {:vhost vhost :request req} options)))))
            (dissoc :route)))))
     (:vhosts vhosts-model))))

(defn make-handler
  ([vhosts-model] (make-handler vhosts-model identity))
  ([vhosts-model handler-fn]
   (make-handler vhosts-model handler-fn
                 (fn [req]
                   {:status 404 :body "Not found\n"})))
  ([vhosts-model handler-fn not-found]
   (fn [req]
     (let [{:keys [handler route-params] :as match-context}
           (find-handler vhosts-model req)]
       (if-let [handler (handler-fn handler)]
         (br/request
          handler
          (-> req
              (update-in [:params] merge route-params)
              (update-in [:route-params] merge route-params))
          (apply dissoc match-context :handler (keys req)))
         (not-found req))))))

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
