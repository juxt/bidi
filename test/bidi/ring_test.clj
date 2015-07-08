;; Copyright © 2014, JUXT LTD.

(ns bidi.ring-test
  (:require
   [bidi.ring :refer :all]
   [bidi.bidi :refer :all]
   [clojure.test :refer :all]
   [ring.mock.request :refer (request) :rename {request mock-request}]))

(deftest make-handler-test

  (testing "routes"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [["/index.html" (fn [req] {:status 200 :body "Index"})]
                            [["/article/" :id ".html"] 'blog-article-handler]
                            [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                          ["images/" 'image-handler]]])]
      (is (= (handler (mock-request :get "/blog/index.html"))
             {:status 200 :body "Index"}))))

  (testing "method constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get [["/index.html" (fn [req] {:status 200 :body "Index"})]]]
                            [:post [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
                           ]]])]

      (is handler)
      (is (= (handler (mock-request :get "/blog/index.html")) {:status 200 :body "Index"}))
      (is (nil? (handler (mock-request :post "/blog/index.html"))))
      (is (= (handler (mock-request :post "/blog/zip")) {:status 201 :body "Created"}))
      (is (nil? (handler (mock-request :get "/blog/zip"))))))

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
      (is (nil? (handler (mock-request :post "/blog/zip"))))
      (is (= (handler (mock-request :post "http://juxt.pro/blog/zip"))
             {:status 201 :body "Created"}))
      (is (nil? (handler (mock-request :post "/blog/zip"))))
      (testing "artid makes it into :route-params"
        (is (= (handler (mock-request :get "/blog/article/123/article.html"))
               {:status 200 :body "123"})))))

  (testing "applying optional function to handler"

    (let [handler-lookup {:my-handler (fn [req] {:status 200 :body "Index"})}
          handler (make-handler ["/" :my-handler] (fn [handler-id] (handler-id handler-lookup)))]
      (is handler)
      (is (= (handler (mock-request :get "/")) {:status 200 :body "Index"}))))

  (testing "using handler vars"
    (defn test-handler [req] {:status 200 :body "Index"})
    (let [handler
          (make-handler ["/"
                         [["" (-> #'test-handler (tag :index))]]])]
      (is handler)
      (is (= (handler (mock-request :get "/"))
             {:status 200 :body "Index"})))))

(deftest route-params-hygiene-test
  (let [handler
        (make-handler [["/blog/user/" :userid "/article"]
                       (fn [req] {:status 201 :body (:route-params req)})])]

    (is handler)
    (testing "specified params like userid make it into :route-params
                but other params do not"
      (is (= (handler (-> (mock-request :put "/blog/user/8888/article")
                          (assoc :params {"foo" "bar"})))
             {:status 201 :body {:userid "8888"}})))))


(deftest redirect-test
  (let [content-handler (fn [req] {:status 200 :body "Some content"})
        routes ["/articles/"
                [[[:artid "/new"] content-handler]
                 [[:artid "/old"] (->Redirect 307 content-handler)]]]
        handler (make-handler routes)]
    (is (= (handler (mock-request :get "/articles/123/old"))
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

(deftest tagger-handlers
  (let [routes ["/" [["foo" (tag  (fn [req] "foo!") :foo)]
                     [["bar/" :id] (tag (fn [req] "bar!") :bar)]]]]
    (is (= ((make-handler routes) (mock-request :get "/foo")) "foo!"))
    (is (= ((make-handler routes) (mock-request :get "/bar/123")) "bar!"))
    (is (= (path-for routes :foo) "/foo"))
    (is (= (path-for routes :bar :id "123") "/bar/123"))))

(deftest unresolve-handlers
  (testing "Redirect"
    (let [foo (->Redirect 303 "/home/foo")
          bar (->Redirect 303 "/home/bar")
          routes ["/" [["foo" foo]
                       ["bar" bar]]]]
      (is (= "/foo" (path-for routes foo)))
      (is (= "/bar" (path-for routes bar)))))
  (testing "Resources"
    (let [foo (->Resources {:id :foo})
          bar (->Resources {:id :bar})
          routes ["/" [["foo" foo]
                       ["bar" bar]]]]
      (is (= "/foo" (path-for routes foo)))
      (is (= "/bar" (path-for routes bar)))))
  (testing "ResourcesMaybe"
    (let [foo (->ResourcesMaybe {:id :foo})
          bar (->ResourcesMaybe {:id :bar})
          routes ["/" [["foo" foo]
                       ["bar" bar]]]]
      (is (= "/foo" (path-for routes foo)))
      (is (= "/bar" (path-for routes bar)))))
  (testing "Files"
    (let [foo (->Files {:id :foo})
          bar (->Files {:id :bar})
          routes ["/" [["foo" foo]
                       ["bar" bar]]]]
      (is (= "/foo" (path-for routes foo)))
      (is (= "/bar" (path-for routes bar))))))
