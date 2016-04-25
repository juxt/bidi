;; Copyright © 2014, JUXT LTD.

(ns bidi.vhosts-test
  (:require
   [clojure.test :refer :all]
   [schema.core :as s]
   [schema.utils :refer [error?]]
   [bidi.bidi :refer [path-for]]
   [bidi.vhosts :refer :all]))

(def example-vhosts-model
  (vhosts-model
   [[{:scheme :https :host "a.org"}
     {:scheme :http :host "a.org"}
     {:scheme :http :host "www.a.org"}
     {:scheme :https :host "www.a.org"}]
    ["/index" :a]]

   [{:scheme :https :host "b.org"}
    [["/b/" :n "/b1.html"] :b1]
    [["/b/" :n "/b2.html"] :b2]]

   [[{:scheme :http :host "c.com:8000"}
     {:scheme :https :host "c.com:8001"}]
    ["/index.html" :c]
    ["/x" :x]]

   [{:scheme :http :host "d.com:8002"}
    ["/index/" [["d" :d]]]
    ;; :x is in both this and the one above
    ["/dir/x" :x]]))

(deftest find-handler-test
  (is (= :c (:handler (find-handler
                       example-vhosts-model
                       ;; Ring request
                       {:scheme :http
                        :headers {"host" "c.com:8000"}
                        ;; Ring confusingly calls the URI's path
                        :uri "/index.html"})) )))

(deftest uri-for-test
  (let [raw-model example-vhosts-model
        model (prioritize-vhosts raw-model nil)]
    (testing "uris"
      (is (= "https://a.org/index" (:uri (uri-for model :a {:vhost {:scheme :https :host "a.org"}}))))
      (is (= "http://c.com:8000/index.html" (:uri (uri-for model :c {:vhost {:scheme :http :host "c.com:8000"}}))))
      (is (= "http://d.com:8002/index/d" (:uri (uri-for model :d {:vhost {:scheme :http :host "d.com:8002"}})))))

    (testing "route-params"
      (is (= "https://b.org/b/1/b1.html" (:uri (uri-for model :b1 {:route-params {:n 1}
                                                                   :vhost {:scheme :https :host "b.org"}}))))
      (is (= "https://b.org/b/abc/b2.html" (:uri (uri-for model :b2 {:route-params {:n "abc"}
                                                                     :vhost {:scheme :https :host "b.org"}})))))

    (testing "relative"
      (is (= "http://a.org/index" (:uri (uri-for model :a {:vhost {:scheme :http :host "a.org"}}))))
      (is (= "http://c.com:8000/index.html" (:uri (uri-for model :c {:vhost {:scheme :http :host "c.com:8000"}}))))
      (is (= "https://c.com:8001/index.html" (:uri (uri-for model :c {:vhost {:scheme :https :host "c.com:8001"}})))))

    (testing "same scheme is preferred by default"
      (is (= "http://www.a.org/index" (:uri (uri-for model :a {:vhost {:scheme :http :host "www.a.org"}}))))
      (is (= "https://www.a.org/index" (:uri (uri-for model :a {:vhost {:scheme :https :host "www.a.org"}})))))


    (testing "query params"
      (is (= "https://b.org/b/1/b1.html?foo=bar"
             (:uri (uri-for model :b1 {:route-params {:n 1}
                                       :query-params {"foo" "bar"}
                                       :vhost {:scheme :https :host "b.org"}}))))
      (is (= "https://b.org/b/1/b1.html?foo=bar&foo=fry%26laurie"
             (:uri (uri-for model :b1 {:route-params {:n 1}
                                       :query-params {"foo" ["bar" "fry&laurie"]}
                                       :vhost {:scheme :https :host "b.org"}})))))))

(deftest duplicate-routes-test
  (testing "same vhost takes priority"
    (is (= "https://c.com:8001/x" (:uri (uri-for (prioritize-vhosts example-vhosts-model {:scheme :https :host "c.com:8001"}) :x {:prefer :https}))))
    (is (= "http://d.com:8002/dir/x" (:uri (uri-for (prioritize-vhosts example-vhosts-model {:scheme :http :host "d.com:8002"}) :x))))))

(deftest make-handler-test
  (let [h (make-handler example-vhosts-model
                        {:c (fn [req] {:status 200})})]
    (is (= {:status 200}
           (h
            {:scheme :http
             :headers {"host" "c.com:8000"}
             :uri "/index.html"})))))

(deftest redirect-test
  (let [model (vhosts-model
               [[{:scheme :https :host "a.org"}
                 {:scheme :http :host "www.a.org"}]
                ["" [["/index" :a] ["/" (redirect :a)]]]])
        h (make-handler model)]

    (let [resp (h {:scheme :http
                   :headers {"host" "www.a.org"}
                   ;; Ring confusingly calls the URI's path
                   :uri "/"})]
      (is (= 302 (:status resp)))
      (is (= "http://www.a.org/index" (get-in resp [:headers "location"]))))))

(deftest coercion-test
  (testing "coercions"
    (let [m
          (coerce-to-vhosts-model
           [
            ["https://abc.com"
             ["/" :a/index]
             ["/foo" :a/foo]
             ]
            [{:scheme :http :host "abc"}
             ["/" :b/index]
             ["/bar" :b/bar]
             ]
            [[{:scheme :http :host "localhost"} "http://def.org"]
             ["/" :c/index]
             ["/zip" :c/zip]
             ]])]
      (is (not (error? m)))
      (is (= [[[{:scheme :https, :host "abc.com"}] ["/" :a/index] ["/foo" :a/foo]]
              [[{:scheme :http, :host "abc"}] ["/" :b/index] ["/bar" :b/bar]]
              [[{:scheme :http, :host "localhost"}
                {:scheme :http, :host "def.org"}]
               ["/" :c/index]
               ["/zip" :c/zip]]] m))))

  (testing "synonymous vhosts"
    (is (nil? (:error (coerce-to-vhosts-model
                       [[["http://localhost:8000"
                          "http://localhost:8001"]
                         ["/" :index]
                         ]])))))

  (testing "cannot have empty vhosts"
    (is (:error (coerce-to-vhosts-model
                 [[[]
                   ["/" :index]
                   ]])))))
