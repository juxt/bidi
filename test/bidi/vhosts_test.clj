;; Copyright © 2014, JUXT LTD.

(ns bidi.vhosts-test
  (:require
   [clojure.test :refer :all]
   [schema.core :as s]
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
    ["/index.html" :c]]

   [{:scheme :http :host "d.com:8002"}
    ["/index/" [["d" :d]]]]))

(deftest find-handler-test
  (is (= :c (:handler (find-handler
                       example-vhosts-model
                       ;; Ring request
                       {:scheme :http
                        :headers {"host" "c.com:8000"}
                        ;; Ring confusingly calls the URI's path
                        :uri "/index.html"})) )))

(deftest uri-for-test
  (let [model example-vhosts-model]
    (testing "uris"
      (is (= "https://a.org/index" (:uri (uri-for model :a))))
      (is (= "http://c.com:8000/index.html" (:uri (uri-for model :c))))
      (is (= "http://d.com:8002/index/d" (:uri (uri-for model :d)))))

    (testing "path-params"
      (is (= "https://b.org/b/1/b1.html" (:uri (uri-for model :b1 {:path-params {:n 1}}))))
      (is (= "https://b.org/b/abc/b2.html" (:uri (uri-for model :b2 {:path-params {:n "abc"}})))))

    (testing "relative"
      (is (= "http://a.org/index" (:uri (uri-for model :a {:vhost {:scheme :http :host "a.org"}}))))
      (is (= "http://c.com:8000/index.html" (:uri (uri-for model :c {:vhost {:scheme :http :host "c.com:8000"}}))))
      (is (= "https://c.com:8001/index.html" (:uri (uri-for model :c {:vhost {:scheme :https :host "c.com:8001"}})))))

    (testing "same scheme is preferred by default"
      (is (= "http://a.org/index" (:uri (uri-for model :a {:vhost {:scheme :http :host "www.a.org"}}))))
      (is (= "https://a.org/index" (:uri (uri-for model :a {:vhost {:scheme :https :host "www.a.org"}})))))

    (testing "query params"
      (is (= "https://b.org/b/1/b1.html?foo=bar"
             (:uri (uri-for model :b1 {:path-params {:n 1}
                                       :query-params {"foo" "bar"}}))))
      (is (= "https://b.org/b/1/b1.html?foo=bar&foo=fry%26laurie"
             (:uri (uri-for model :b1 {:path-params {:n 1}
                                       :query-params {"foo" ["bar" "fry&laurie"]}})))))))

(deftest make-handler-test
  (let [h (make-handler example-vhosts-model
                        {:c (fn [req] {:status 200})})]
    (is (= {:status 200}
           (h
            {:scheme :http
             :headers {"host" "c.com:8000"}
             :uri "/index.html"})))))


