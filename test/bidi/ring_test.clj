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

(ns bidi.ring-test
  (:require [bidi.ring :refer :all]
            [bidi.bidi :refer :all]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]))

(deftest make-handler-test

  (testing "routes"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [["/index.html" (fn [req] {:status 200 :body "Index"})]
                            [["/article/" :id ".html"] 'blog-article-handler]
                            [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                          ["images/" 'image-handler]]])]
      (is (= (handler (request :get "/blog/index.html"))
             {:status 200 :body "Index"}))))

  (testing "method constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get [["/index.html" (fn [req] {:status 200 :body "Index"})]]]
                            [:post [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
                           ]]])]

      (is handler)
      (is (= (handler (request :get "/blog/index.html")) {:status 200 :body "Index"}))
      (is (nil? (handler (request :post "/blog/index.html"))))
      (is (= (handler (request :post "/blog/zip")) {:status 201 :body "Created"}))
      (is (nil? (handler (request :get "/blog/zip"))))))

  (testing "other request constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get
                             [["/index"
                               (fn [req] {:status 200 :body "Index"})]
                              [["/article/" :artid "/article.html"]
                               (fn [req] {:status 200 :body (get-in req [:route-params :artid])})]
                              ]]
                            [{:request-method :post :server-name "juxt.pro"}
                             [["/zip"
                               (fn [req] {:status 201 :body "Created"})]]]]]]])]

      (is handler)
      (is (nil? (handler (request :post "/blog/zip"))))
      (is (= (handler (request :post "http://juxt.pro/blog/zip"))
             {:status 201 :body "Created"}))
      (is (nil? (handler (request :post "/blog/zip"))))
      (testing "artid makes it into :route-params"
        (is (= (handler (request :get "/blog/article/123/article.html"))
               {:status 200 :body "123"})))))

  (testing "applying optional function to handler"

    (let [handler-lookup {:my-handler (fn [req] {:status 200 :body "Index"})}
          handler (make-handler ["/" :my-handler] (fn [handler-id] (handler-id handler-lookup)))]
      (is handler)
      (is (= (handler (request :get "/")) {:status 200 :body "Index"})))))

(deftest redirect-test
  (let [content-handler (fn [req] {:status 200 :body "Some content"})
        routes ["/articles/"
                [[[:artid "/new"] content-handler]
                 [[:artid "/old"] (->Redirect 307 content-handler)]]]
        handler (make-handler routes)]
    (is (= (handler (request :get "/articles/123/old"))
           {:status 307, :headers {"Location" "/articles/123/new"}, :body "Redirect to /articles/123/new"} ))))

(deftest wrap-middleware-test
  (let [wrapper (fn [h] (fn [req] (assoc (h req) :wrapper :evidence)))
        handler (fn [req] {:status 200 :body "Test"})]
    (is (= ((:handler (match-route ["/index.html" (->WrapMiddleware handler wrapper)] "/index.html"))
            {:uri "/index.html"})
           {:wrapper :evidence :status 200 :body "Test"}))

    (is (= ((:handler (match-route ["/index.html" (->WrapMiddleware handler wrapper)] "/index.html"))
            {:path-info "/index.html"})
           {:wrapper :evidence :status 200 :body "Test"}))

    (is (= (path-for ["/index.html" (->WrapMiddleware handler wrapper)] handler) "/index.html"))
    (is (= (path-for ["/index.html" handler] handler) "/index.html"))))

(deftest wrap-alternates-test
  (let [routes [(->Alternates ["/index.html" "/index"]) :index]]
    (is (= (match-route routes "/index.html") {:handler :index}))
    (is (= (match-route routes "/index") {:handler :index}))
    (is (= (path-for routes :index) "/index.html")) ; first is the canonical one
    (is (= #{} (route-params routes :index)))))

(deftest labelled-handlers
  (let [routes ["/" [["foo" (->TaggedMatch :foo (fn [req] "foo!"))]
                     [["bar/" :id] (->TaggedMatch :bar (fn [req] "bar!"))]]]]
    (is (= ((make-handler routes) (request :get "/foo")) "foo!"))
    (is (= ((make-handler routes) (request :get "/bar/123")) "bar!"))
    (is (= (path-for routes :foo) "/foo"))
    (is (= #{} (route-params routes :z)))
    (is (= (path-for routes :bar :id "123") "/bar/123"))
    (is (= #{:id} (route-params routes :bar)))))

(deftest route-params-hygiene-test
  (let [handler
        (make-handler [["/blog/user/" :userid "/article"]
                       (fn [req] {:status 201 :body (:route-params req)})])]

    (is handler)
    (testing "specified params like userid make it into :route-params
                but other params do not"
      (is (= (handler (-> (request :put "/blog/user/8888/article")
                          (assoc :params {"foo" "bar"})))
             {:status 201 :body {:userid "8888"}})))))
