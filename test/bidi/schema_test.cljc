;; Copyright © 2014-2015, JUXT LTD.

(ns bidi.schema-test
  (:require
    #?(:clj  [clojure.test :refer :all]
       :cljs [cljs.test :refer-macros [deftest is testing]])
    [schema.core :as s]
    #?(:clj [schema.macros :refer [if-cljs]])
    [bidi.bidi :as bidi]
    [bidi.schema :as bs])
  #?(:cljs (:require-macros [bidi.schema-test :refer [is-valid is-invalid testing-schema]])))

;; TODO

(def ^{:dynamic true :tag s/Schema} *schema* nil)

(defmacro is-valid
  ([actual]
    `(is-valid *schema* ~actual))
  ([schema actual]
   `(schema.macros/if-cljs
      (cljs.test/is (= nil (s/check ~schema ~actual)))
      (clojure.test/is (= nil (s/check ~schema ~actual))))))

(defmacro is-invalid
  ([expected actual]
    `(is-invalid *schema* ~expected ~actual))
  ([schema expected actual]
   `(schema.macros/if-cljs
      (cljs.test/is (= (str (quote ~expected)) (str (s/check ~schema ~actual))))
      (clojure.test/is (= (str (quote ~expected)) (str (s/check ~schema ~actual)))))))

(defmacro testing-schema [name schema & args]
  `(schema.macros/if-cljs
     (cljs.test/testing ~name
       (binding [*schema* ~schema]
         ~@args))
     (clojure.test/testing ~name
       (binding [*schema* ~schema]
         ~@args))))


(deftest schema-test
  (testing-schema "route pairs" bs/RoutePair
    (testing "simple"
      (is-valid ["/index/" :index])

      (is-valid ["/index/" [["a" :alpha]
                            ["b" :beta]
                            ["c" [["z" :zeta]]]]])

      (is-valid ["/index/" {"a" :alpha
                            "b" :beta
                            "c" {"z" :zeta}}]))

    (testing "path segments"
      (is-valid [["/" :i] :target])
      (is-invalid [(named (not (not-empty [])) "Pattern") nil]
                  [[] :test]))

    (testing "qualified path segments"
      (is-valid [["/" [#".*" :i]] :target])
      (is-valid [["/" [keyword :i]] :target])
      (is-valid [["/" [long :i]] :target])
      (is-valid [["/" [bidi/uuid :i]] :target])

      (is-invalid
        [(named [nil [(named (not (instance? #?(:clj  java.util.regex.Pattern
                                                :cljs js/RegExp)
                                             "muh"))
                             "qual")
                      nil]]
                "Pattern")
         nil]
        [["/" ["muh" :i]] :target])

      (is-invalid
        [(named [nil [(named (not #?(:clj  (bidi.schema/valid-qualifier-function? a-clojure.core$symbol)
                                     :cljs (bidi$schema$valid-qualifier-function? a-function)))
                             "qual")
                      nil]]
                "Pattern")
         nil]
        [["/" [symbol :i]] :target]))

    (testing "method guards"
      (is-valid ["/" {:get   :get-handler
                      :post  :post-handler
                      :patch :patch-handler}])
      (is-valid [:get :test-handler])
      (is-invalid [nil (named {(not (matches-some-precondition? #?(:clj a-clojure.lang.Keyword
                                                                   :cljs a-object)))
                               invalid-key}
                              "Matched")]
                  ["/" {:not-a-recognised-method :handler}]))

    (testing "general guards"
      (is-valid
        ["/" {"blog" {:get
                      {"/index" (fn [req] {:status 200 :body "Index"})}}
              {:request-method :post :server-name "juxt.pro"}
                     {"/zip" (fn [req] {:status 201 :body "Created"})}}]))

    (testing "wrong patterns"
      (is-invalid [(named (not (matches-some-precondition? :test)) "Pattern") nil]
                  [:test :test-handler])
      (is-invalid [(named (not (matches-some-precondition? 12)) "Pattern") nil]
                  [12 :test])
      (is-invalid
        [(named {(not (#?(:clj  keyword?
                          :cljs cljs$core$keyword?)
                        14))
                 invalid-key}
                "Pattern")
         nil]
        [{14 12} :test])
      (is-invalid
        [(named {(not (#?(:clj  keyword?
                          :cljs cljs$core$keyword?)
                        "test"))
                 invalid-key}
                "Pattern")
         nil]
        [{"test" 12} :test]))

    (testing "common mistake"
      (is-invalid [nil (named [(not (sequential? "a"))
                               (not (sequential? :alpha))] "Matched")
                   (not (has-extra-elts? 2))]
                  ["/index/"
                   ["a" :alpha]
                   ["b" :beta]
                   ["c" [["z" :zeta]]]]))

    (testing "sets"
      (is-valid ["/index/" [[#{"foo" "bar"} :foo-or-bar]]])
      (is-invalid [(named (not (not-empty #{})) "Pattern") nil]
                  [#{} :test]))

    (testing "alts"
      (is-valid [(bidi/alts) :empty])
      (is-valid ["/index/" [[(bidi/alts "foo" "bar") :foo-or-bar]]]))))

