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
  #+cljs (:require-macros [cemerick.cljs.test :refer [is testing deftest]])
  (:require #+clj [clojure.test :refer :all]
            #+cljs [cemerick.cljs.test :as t]
            [bidi.bidi :as bidi :refer [match-route
                                        path-for
                                        path-with-query-for
                                        route-params]]))

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

      #+clj
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
       (thrown? #+clj clojure.lang.ExceptionInfo #+cljs cljs.core.ExceptionInfo
                (path-for routes 'archive-handler :id 1239)
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
        (is (= #{:id :a} (route-params routes 'foo)))))))


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

(deftest path-with-query-for-test
  (let [routes [["/blog/user/" :userid "/article"] :index]]

    (is (= (path-with-query-for routes :index :userid 123)
           "/blog/user/123/article"))
    (is (= (path-with-query-for routes :index :userid 123 :page 1)
           "/blog/user/123/article?page=1"))
    (is (= (path-with-query-for routes :index :userid 123 :page 1 :foo "bar")
           "/blog/user/123/article?foo=bar&page=1"))))

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
