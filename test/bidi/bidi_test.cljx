;; Copyright © 2014, JUXT LTD.

(ns bidi.bidi-test
  #+cljs (:require-macros [cemerick.cljs.test :refer [is testing deftest]])
  (:require #+clj [clojure.test :refer :all]
            #+cljs [cemerick.cljs.test :as t]
            [bidi.bidi :as bidi
             :refer [match-route compile-route path-for ->Alternates gather-from-pair context]]))

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
      (is (= (match-route ["/blog" [[["/articles/" [#"[0-9]+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123/index.html")
             {:handler 'foo :route-params {:id "123"}}))
      (is (= (match-route ["/blog" [[["/articles/" [#"[0-9]+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123a/index.html")
             nil))

      (is (= (match-route ["/blog" [[["/articles/" [#"[0-9]+" :id] [#"[a-z]+" :a] "/index.html"] 'foo]
                                    ["/text" 'bar]]]
                          "/blog/articles/123abc/index.html")
             {:handler 'foo :route-params {:id "123" :a "abc"}}))

      #+clj
      (is (= (match-route [#"/bl[a-z]{2}+" [[["/articles/" [#"[0-9]+" :id] [#"[a-z]+" :a] "/index.html"] 'foo]
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
          "/images/123.png")))

    (testing "unmatching with constraints"

      (let [routes ["/" [["blog"
                          [[:get [[["/index"] :index]]]
                           [{:request-method :post :server-name "juxt.pro"}
                            [[["/articles/" :artid] :new-article-handler]]]]]]]]
        (is (= (path-for routes :index)
               "/blog/index"))
        (is (= (path-for routes :new-article-handler :artid 10)
               "/blog/articles/10"))))

    (testing "unmatching with regexes"
      (let [routes
            ["/blog" [[["/articles/" [#"[0-9]+" :id] [#"[a-z]+" :a] "/index.html"] 'foo]
                      ["/text" 'bar]]]]
        (is (= (path-for routes 'foo :id "123" :a "abc")
               "/blog/articles/123abc/index.html"))))

    (testing "unmatching with nil handlers" ; issue #28
      (let [routes ["/" {"foo" nil "bar" :bar}]]
        (is (= (path-for routes :bar) "/bar"))))))

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

(deftest keywords
  (let [routes ["/" [["foo/" :x]
                     [["foo/" [keyword :id]] :y]
                     [["foo/" [keyword :id] "/bar"] :z]]]]
    (is (= (:handler (match-route routes "/foo/")) :x))

    (is (= (:handler (match-route routes "/foo/abc")) :y))
    (is (= (:route-params (match-route routes "/foo/abc")) {:id :abc}))
    (is (= (:route-params (match-route routes "/foo/abc%2Fdef")) {:id :abc/def}))
    (is (= (path-for routes :y :id :abc) "/foo/abc"))
    (is (= (path-for routes :y :id :abc/def) "/foo/abc%2Fdef"))

    (is (= (:handler (match-route routes "/foo/abc/bar")) :z))
    (is (= (path-for routes :z :id :abc) "/foo/abc/bar"))))

(deftest long-test
  (let [routes ["/" [["foo/" :x]
                     [["foo/" [long :id]] :y]
                     [["foo/" [long :id] "/bar"] :z]]]]
    (is (= (:handler (match-route routes "/foo/")) :x))

    (is (= (:handler (match-route routes "/foo/345")) :y))
    (is (= (:route-params (match-route routes "/foo/345")) {:id 345}))
    (is (= (path-for routes :y :id -1000) "/foo/-1000"))
    (is (= (path-for routes :y :id 1234567) "/foo/1234567"))

    (is (= (:handler (match-route routes "/foo/0/bar")) :z))
    (is (= (path-for routes :z :id 12) "/foo/12/bar"))

    (testing "bigger than longs"
      (is (nil? (match-route routes "/foo/1012301231111111111111111111"))))))

(deftest uuid-test
  (let [routes ["/" [["foo/" :x]
                     [["foo/" [bidi/uuid :id]] :y]
                     [["foo/" [bidi/uuid :id] "/bar"] :z]]]]
    (is (= (:handler (match-route routes "/foo/")) :x))

    (is (= (:handler (match-route routes "/foo/649a50e8-0342-47af-894e-27eefea83ca9"))
           :y))
    (is (= (:route-params (match-route routes "/foo/649a50e8-0342-47af-894e-27eefea83ca9"))
           {:id #uuid "649a50e8-0342-47af-894e-27eefea83ca9"}))
    (is (= (path-for routes :y :id #uuid "649a50e8-0342-47af-894e-27eefea83ca9")
           "/foo/649a50e8-0342-47af-894e-27eefea83ca9"))

    (is (= (:handler (match-route routes "/foo/649a50e8-0342-47af-894e-27eefea83ca9/bar")) :z))
    (is (= (path-for routes :z :id #uuid "649a50e8-0342-47af-894e-27eefea83ca9")
           "/foo/649a50e8-0342-47af-894e-27eefea83ca9/bar"))

    (testing "invalid uuids"
      (is (nil? (match-route routes "/foo/649a50e8-0342-67af-894e-27eefea83ca9")))
      (is (nil? (match-route routes "/foo/649a50e8-0342-47af-c94e-27eefea83ca9")))
      (is (nil? (match-route routes "/foo/649a50e8034247afc94e27eefea83ca9")))
      (is (nil? (match-route routes "/foo/1012301231111111111111111111"))))))

(deftest wrap-alternates-test
  (let [routes [(->Alternates ["/index.html" "/index"]) :index]]
    (is (= (match-route routes "/index.html") {:handler :index}))
    (is (= (match-route routes "/index") {:handler :index}))
    (is (= (path-for routes :index) "/index.html")) ; first is the canonical one
    ))

(deftest compile-routes-test
  (testing "basic route"
    (let [routes [["/foo/" :bar] :foo]
          compiled (compile-route routes)
          path "/foo/hello"
          match {:handler      :foo
                 :route-params {:bar "hello"}}]
      (is (= (match-route routes path) match))
      (is (= (match-route compiled path) match))))
  (testing "route with regex"
    (let [routes [["/foo/" [#".*" :extra]] :foo]
          compiled (compile-route routes)
          path "/foo/hello/bar/baz"
          match {:handler      :foo
                 :route-params {:extra "hello/bar/baz"}}]
      (is (= (match-route routes path) match))
      (is (= (match-route compiled path) match))))
  #_(testing "issue #63" ; can't get this working under cljs, need help!
    (let [routes ["/" {"index.html" :index
                       "article.html" :article}]
          compiled-routes (compile-route routes)]
      (is (= (path-for routes :index) "/index.html"))
      (is (= (path-for compiled-routes :index) "/index.html")))
    ))


(deftest gather
  (let [myroutes
        ["/" [
              ["index" :index]
              ["docs/" [
                        [[:doc-id "/"]
                         [["view" :docview]
                          [["chapter/" :chapter "/"] {"view" :chapter-view}]]]]]]]

        result (gather-from-pair ["" [myroutes]])]
    (is (= (count result) 3))))

(defn partial-map [m]
  (fn [context]
    (merge-with merge context {::map m})))

(def secure (partial context (partial-map {:secure true :level :root})))

(deftest gather-with-partial
  (let [myroutes
        ["/"
         {"public/"
          {"index" :public-index}
          "secure/"
          (secure
           [
            ["index" :secure-index]
            ["docs/" (context
                      (partial-map {:level :docs})
                      [
                       ["index" :docs-index]
                       [[:doc-id "/"]
                        (context (partial-map {:level :doc})
                                 [["view" :docview]
                                  [["chapter/" :chapter "/"] {"view" :chapter-view}]])]])]])}]
        result (gather-from-pair myroutes)]
    #_(pprint result)
    (is (= (count result) 5))))
