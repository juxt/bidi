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

(ns bidi.bidi-test
  (:require [clojure.test :refer :all]
            [bidi.bidi :refer :all]
            [ring.mock.request :refer :all]))

(deftest matching-routes-test
  (testing "misc-routes"
    (is (= (match-route ["/blog/foo" 'foo] "/blog/foo")
           {:handler 'foo}))

    ;; In the case of a partial match, the right hand side of a pair can
    ;; contain further candidates to try. Multiple routes are contained
    ;; in a vector and tried in order.
    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  ["/bar" [["/abc" :bar]]]]]
                        "/blog/bar/abc")
           {:handler :bar}))

    ;; If no determinstic order is required, a map can also be used.
    (is (= (match-route ["/blog" {"/foo" 'foo
                                  "/bar" [["/abc" :bar]]}]
                        "/blog/bar/abc")
           {:handler :bar}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  [["/bar" [#".*" :path]] :bar]]]
                        "/blog/bar/articles/123/index.html")
           {:handler :bar :route-params {:path "/articles/123/index.html"}}))

    ;; The example in the README, so make sure it passes!
    (is (= (match-route ["/blog" [["/index.html" 'index]
                                  [["/bar/articles/" :artid "/index.html"] 'article]]]
                        "/blog/bar/articles/123/index.html")
           {:handler 'article :route-params {:artid "123"}}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  [["/bar/articles/" :artid "/index.html"] 'bar]]]
                        "/blog/bar/articles/123/index.html")
           {:handler 'bar :route-params {:artid "123"}}))

    (is (= (match-route ["/blog" [[["/articles/" :id "/index.html"] 'foo]
                                  ["/text" 'bar]]]
                        "/blog/articles/123/index.html")
           {:handler 'foo :route-params {:id "123"}}))

    (testing "regex"
      (is (= (match-route ["/blog" [[["/articles/" [#"\d+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123/index.html")
             {:handler 'foo :route-params {:id "123"}}))
      (is (= (match-route ["/blog" [[["/articles/" [#"\d+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123a/index.html")
             nil))
      (is (= (match-route ["/blog" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123abc/index.html")
             {:handler 'foo :route-params {:id "123" :a "abc"}}))

      (is (= (match-route [#"/bl\p{Lower}{2}+" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                                                ["/text" 'bar]]]
                          "/blog/articles/123abc/index.html")
             {:handler 'foo :route-params {:id "123" :a "abc"}}))

      (is (= (match-route [["/blog/articles/123/" :path] 'foo]
                          "/blog/articles/123/index.html")
             {:handler 'foo :route-params {:path "index.html"}})))

    (testing "boolean patterns"
      (is (= (match-route [true :index] "/any") {:handler :index}))
      (is (= (match-route [false :index] "/any") nil)))))

(deftest unmatching-routes-test
  (let [routes ["/"
                [["blog"
                  [["/index.html" 'blog-index]
                   [["/article/" :id ".html"] 'blog-article-handler]
                   [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                 [["images/" :path] 'image-handler]]]]

    (testing "unmatching"

      (is
       (= (path-for routes 'blog-index)
          "/blog/index.html"))
      (is
       (= (path-for routes 'blog-article-handler :id 1239)
          "/blog/article/1239.html"))
      (is
       ;; If not all the parameters are specified we expect an error to be thrown
       (thrown? clojure.lang.ExceptionInfo (path-for routes 'archive-handler :id 1239)
                "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'archive-handler :id 1239 :page "section")
          "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'image-handler :path "")
          "/images/"))

      (is
       (= (path-for routes 'image-handler :path "123.png")
          "/images/123.png"))
      (is (= #{:path} (route-params routes 'image-handler))))

    (testing "unmatching with constraints"

      (let [routes ["/" [["blog"
                          [[:get [[["/index"] :index]]]
                           [{:request-method :post :server-name "juxt.pro"}
                            [[["/articles/" :artid] :new-article-handler]]]]]]]]
        (is (= (path-for routes :index)
               "/blog/index"))
        (is (= (path-for routes :new-article-handler :artid 10)
               "/blog/articles/10"))
        (is (= #{:artid} (route-params routes :new-article-handler)))))
    (testing "unmatching with regexes"
      (let [routes
            ["/blog" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                      ["/text" 'bar]]]]
        (is (= (path-for routes 'foo :id "123" :a "abc")
               "/blog/articles/123abc/index.html"))
        (is (= #{:id :a} (route-params routes 'foo)))
        ))))


(deftest unmatching-routes-with-anonymous-fns-test
  (testing "unmatching when routes contains a ref to anonymous function(s) should not throw exception"
    (let [routes
          ["/blog" [["/index.html" (fn [req] {:status 200 :body "Index"})]
                    ["/list" 'list-blogs]
                    ["/temp.html" :temp-html]]]]
      (is (= (path-for routes 'list-blogs)
             "/blog/list"))
      (is (= (path-for routes :temp-html)
             "/blog/temp.html")))))


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


(deftest keywords
  (let [routes ["/" [["foo/" :x]
                     [["foo/" [keyword :id]] :y]
                     [["foo/" [keyword :id] "/bar"] :z]]]]
    (is (= (:handler (match-route routes "/foo/")) :x))
    (is (= #{} (route-params routes :x)))

    (is (= (:handler (match-route routes "/foo/abc")) :y))
    (is (= (:route-params (match-route routes "/foo/abc")) {:id :abc}))
    (is (= (:route-params (match-route routes "/foo/abc%2Fdef")) {:id :abc/def}))
    (is (= (path-for routes :y :id :abc) "/foo/abc"))
    (is (= (path-for routes :y :id :abc/def) "/foo/abc%2Fdef"))
    (is (= #{:id} (route-params routes :y)))

    (is (= (:handler (match-route routes "/foo/abc/bar")) :z))
    (is (= (path-for routes :z :id :abc) "/foo/abc/bar"))
    (is (= #{:id} (route-params routes :z)))))

(deftest long-test
  (let [routes ["/" [["foo/" :x]
                     [["foo/" [long :id]] :y]
                     [["foo/" [long :id] "/bar"] :z]]]]
    (is (= (:handler (match-route routes "/foo/")) :x))
    (is (= #{} (route-params routes :x)))

    (is (= (:handler (match-route routes "/foo/345")) :y))
    (is (= (:route-params (match-route routes "/foo/345")) {:id 345}))
    (is (= (path-for routes :y :id -1000) "/foo/-1000"))
    (is (= (path-for routes :y :id 1234567) "/foo/1234567"))
    (is (= #{:id} (route-params routes :y)))

    (is (= (:handler (match-route routes "/foo/0/bar")) :z))
    (is (= (path-for routes :z :id 12) "/foo/12/bar"))
    (is (= #{:id} (route-params routes :z)))

    (testing "bigger than longs"
      (is (nil? (match-route routes "/foo/1012301231111111111111111111"))))))

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

(deftest path-with-query-for-test
  (let [routes [["/blog/user/" :userid "/article"] :index]]

    (is (= (path-with-query-for routes :index :userid 123)
           "/blog/user/123/article"))
    (is (= (path-with-query-for routes :index :userid 123 :page 1)
           "/blog/user/123/article?page=1"))
    (is (= (path-with-query-for routes :index :userid 123 :page 1 :foo "bar")
           "/blog/user/123/article?foo=bar&page=1"))))
