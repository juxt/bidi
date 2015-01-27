;; Copyright © 2014, JUXT LTD.

(ns bidi.perf-test
  (:require
   [clojure.test :refer :all]
   [compojure.core :refer (GET routes)]
   [bidi.bidi :refer :all]
   [bidi.ring :refer :all]
   [ring.mock.request :refer (request)]
   [clojure.walk :refer (postwalk)]
   [clojure.core.match :refer (match)]
    [criterium.core :as criterium]))

;; Here are some Compojure routes, we want to match on the final one.
(deftest compojure-control-test []
  (let [ctx (routes
             (GET "index.html" [] (fn [req] {:status 200 :body "index"}))
             (GET "a.html" [] (fn [req] {:status 200 :body "a"}))
             (GET "b.html" [] (fn [req] {:status 200 :body "b"}))
             (GET "c.html" [] (fn [req] {:status 200 :body "c"}))
             (GET "d.html" [] (fn [req] {:status 200 :body "d"}))
             (GET "e.html" [] (fn [req] {:status 200 :body "e"}))
             )
        req (request :get "e.html")]
    (is (= (ctx req) {:status 200, :headers {}, :body "e"}))
    (println "Time to match using Compojure's routes")
    (criterium/bench (ctx req))))

(deftest perf-test []
  (let [rtes ["/" [["index.html" :index]
                   ["a.html" :a]
                   ["b.html" :b]
                   ["c.html" :c]
                   ["d.html" :d]
                   ["e.html" (fn [req] {:status 200 :body "e"})]]]
        req (request :get "/e.html")]

    (testing "Uncompiled routes"
      (let [h (make-handler rtes)]
        (is (= (h req) {:status 200 :body "e"}))
        (is (= (path-for rtes :d) "/d.html"))
        (println "Time to match using uncompiled bidi routes")
        (criterium/bench (h req))))

    (testing "Compiled routes"
      (let [h (make-handler (compile-route rtes))]
        (is (= (h req) {:status 200 :body "e"}))
        (is (= (path-for rtes :d) "/d.html"))
        (println "Time to match using compiled bidi routes")
        (criterium/bench (h req))))))
