(ns bidi.server-test
  (:require
   [bidi.server :refer :all]
   [bidi.ring :refer :all]
   [bidi.bidi :refer :all]
   [clojure.test :refer :all]
   [ring.mock.request :refer :all]))

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

(deftest labelled-handlers
  (let [routes ["/" [["foo" (->TaggedMatch :foo (fn [req] "foo!"))]
                     [["bar/" :id] (->TaggedMatch :bar (fn [req] "bar!"))]]]]
    (is (= ((make-handler routes) (request :get "/foo")) "foo!"))
    (is (= ((make-handler routes) (request :get "/bar/123")) "bar!"))
    (is (= (path-for routes :foo) "/foo"))
    (is (= (path-for routes :bar :id "123") "/bar/123"))))
