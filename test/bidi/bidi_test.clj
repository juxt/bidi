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
            [bidi.bidi :refer :all]))

(deftest matching-routes-test
  (testing "misc-routes"
    (is (= (match-route ["/blog" 'foo] "/blog/foo")
           {:handler 'foo, :path "/foo"}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  ["/bar" :bar]]]
                        "/blog/foo/abc")
           {:handler 'foo, :path "/abc"}))

    (is (= (match-route ["/blog" [["/foo" 'foo]
                                  ["/bar" :bar]]]
                        "/blog/bar/articles/123/index.html")
           {:handler :bar, :path "/articles/123/index.html"}))

    ;; in README
    (is (=
         (match-route ["/blog" [["/foo" 'foo]
                                [["/bar/articles/" :artid "/index"] 'bar]]]
                      "/blog/bar/articles/123/index.html")
         {:handler 'bar, :params {:artid "123"}, :path ".html"}))

    (is (=
         (match-route ["/blog" [["/foo" 'foo]
                                [["/bar/articles/" :artid "/index.html"] 'bar]]]
                      "/blog/bar/articles/123/index.html")
         {:handler 'bar, :params {:artid "123"}, :path ""}))

    (is (=
         (match-route ["/blog" [[["/articles/" :id "/index.html"] 'foo]
                                ["/text" 'bar]]]
                      "/blog/articles/123/index.html")
         {:handler 'foo, :params {:id "123"}, :path ""}))))

(deftest unmatching-routes-test
  (let [routes ["/"
                [["blog"
                  [["/index.html" 'blog-index]
                   [["/article/" :id ".html"] 'blog-article-handler]
                   [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                 ["images/" 'image-handler]]]]
    (testing "unmatching"
      (is
       (= (path-for routes :handler 'blog-index)
          "/blog/index.html"))
      (is
       (= (path-for routes :handler 'blog-article-handler :params {:id 1239})
          "/blog/article/1239.html"))
      (is
       ;; If not all the parameters are specified, we expect an error to be thrown
       (thrown? clojure.lang.ExceptionInfo (path-for routes :handler 'archive-handler :params {:id 1239})
                "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes :handler 'archive-handler :params {:id 1239 :page "section"})
          "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes :handler 'image-handler)
          "/images/")))))
