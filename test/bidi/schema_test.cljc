;; Copyright © 2014-2015, JUXT LTD.

(ns bidi.schema-test
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [schema.core :as s]
   [bidi.bidi :as bidi]
   [bidi.schema :as bs]))

;; TODO

(deftest schema-test
  (testing "route-pairs"

    (is (nil? (s/check bs/RoutePair ["/index/" :index])))

    (is (nil? (s/check bs/RoutePair ["/index/" [["a" :alpha]
                                                ["b" :beta]
                                                ["c" [["z" :zeta]]]]])))

    (is (nil? (s/check bs/RoutePair ["/index/" {"a" :alpha
                                                "b" :beta
                                                "c" {"z" :zeta}}]))))

  (testing "path segments"
    (is (nil? (s/check bs/RoutePair [["/" :i] :target]))))

  (testing "qualified path segments"
    (is (nil? (s/check bs/RoutePair [["/" [#".*" :i]] :target]))))

  (testing "method guards"
    (is (nil? (s/check bs/RoutePair ["/" {:get :get-handler
                                          :post :post-handler
                                          :patch :patch-handler}])))

    ;; This test now fails since we allowed Patterns in schema
    #_(is (not (nil? (s/check bs/RoutePair ["/" {:not-a-recognised-method :handler}])))))

  (testing "general guards"
    (is (nil?
         (s/check bs/RoutePair
                  ["/" {"blog" {:get
                                {"/index" (fn [req] {:status 200 :body "Index"})}}
                        {:request-method :post :server-name "juxt.pro"}
                        {"/zip" (fn [req] {:status 201 :body "Created"})}}]))))

  (testing "common mistake"
    (is (not (nil? (s/check bs/RoutePair ["/index/"
                                          ["a" :alpha]
                                          ["b" :beta]
                                          ["c" [["z" :zeta]]]])))))

  (testing "patterns"
    (is (nil? (s/check bs/RoutePair
                       ["/index/" [[(bidi/alts "foo" "bar") :foo-or-bar]]])))))

["/index/" [[(bidi/alts "foo" "bar") :foo-or-bar]]]

