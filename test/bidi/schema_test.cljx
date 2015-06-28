;; Copyright © 2014-2015, JUXT LTD.

(ns bidi.schema-test
  #+cljs (:require-macros [cemerick.cljs.test :refer [is testing deftest]])
  (:require #+clj [clojure.test :refer :all]
            #+cljs [cemerick.cljs.test :as t]
            [schema.core :as s]
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
                                                "c" {"z" :zeta}
                                                }]))))

  (testing "path segments"
    (is (nil? (s/check bs/RoutePair [["/" :i] :target]))))

  (testing "qualified path segments"
    (is (nil? (s/check bs/RoutePair [["/" [#".*" :i]] :target]))))

  (testing "method guards"
    (is (nil? (s/check bs/RoutePair ["/" {:get :get-handler
                                          :post :post-handler}]))))

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
                                          ["c" [["z" :zeta]]]]))))))
