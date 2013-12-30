;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns bidi.perf-test
  (:require
   [clojure.test :refer :all]
   [compojure.core :refer (GET routes)]
   [bidi.bidi :refer :all]
   [ring.mock.request :refer (request)]
   [clojure.walk :refer (postwalk)]
   [clojure.core.match :refer (match)]))

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
    (println "Time for 1000 matches using Compojure routes")
    (time
     (dotimes [_ 1000]
       (ctx req)))))

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
        (println "Time for 1000 matches using uncompiled bidi routes")
        (time (dotimes [_ 1000] (h req)))))
    (testing "Compiled routes"
      (let [h (make-handler (compile-route rtes))]
        (is (= (h req) {:status 200 :body "e"}))
        (is (= (path-for rtes :d) "/d.html"))
        (println "Time for 1000 matches using compiled bidi routes")
        (time (dotimes [_ 1000] (h req)))))))
