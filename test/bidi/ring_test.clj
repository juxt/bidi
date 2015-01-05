;; Copyright © 2014, JUXT LTD.

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
