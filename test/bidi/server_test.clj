;; Copyright © 2014, JUXT LTD.

(ns bidi.server-test
  (:require
   [clojure.test :refer :all]
   [schema.core :as s]
   [bidi.bidi :refer [path-for]]
   [bidi.server :refer :all]))

(def example-uri-model
  (uri-model
   [[{:scheme :https :host "a.org" :port 443}
     {:scheme :http :host "a.org" :port 80}
     {:scheme :http :host "www.a.org" :port 80}
     {:scheme :https :host "www.a.org" :port 443}]
    ["/index" :a]]

   [{:scheme :https :host "b.org" :port 443}
    ["/b/b1.html" :b1]
    ["/b/b2.html" :b2]]

   [[{:scheme :http :host "c.com" :port 8000}
     {:scheme :https :host "c.com" :port 8001}]
    ["/index.html" :c]]

   [{:scheme :http :host "d.com" :port 8002}
    ["/index/" [["d" :d]]]]))

(deftest find-handler-test
  (is (= :c (:handler (find-handler
                    example-uri-model
                    {:scheme :http
                     :headers {"host" "c.com"}
                     :server-port 8000
                     :uri "/index.html"})) )))

(deftest uri-for-test
  (let [model example-uri-model]
    (is (= "https://a.org/index" (uri-for model :a)))
    (is (= "https://b.org/b/b1.html" (uri-for model :b1)))
    (is (= "https://b.org/b/b2.html" (uri-for model :b2)))
    (is (= "http://c.com:8000/index.html" (uri-for model :c)))
    (is (= "http://d.com:8002/index/d" (uri-for model :d)))))

(deftest make-handler-test
  (let [h (make-handler example-uri-model
                        {:c (fn [req] {:status 200})})]
    (is (= {:status 200}
           (h
            {:scheme :http
             :headers {"host" "c.com"}
             :server-port 8000
             :uri "/index.html"})))))

