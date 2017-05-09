;; Copyright © 2014, JUXT LTD.

(ns bidi.verbose-test
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [bidi.verbose :refer [branch param leaf]]))

(deftest verbose-syntax-test
  (is (= ["http://localhost:8080"
          [[["/users/" :user-id]
            [["/topics" [["" :topics] ["/bulk" :topic-bulk]]]]]
           [["/topics/" :topic] [["" :private-topic]]]
           ["/schemas" :schemas]
           [["/orgs/" :org-id] [["/topics" :org-topics]]]]]
         (branch
          "http://localhost:8080"
          (branch "/users/" (param :user-id)
                  (branch "/topics"
                          (leaf "" :topics)
                          (leaf "/bulk" :topic-bulk)))
          (branch "/topics/" (param :topic)
                  (leaf "" :private-topic))
          (leaf "/schemas" :schemas)
          (branch "/orgs/" (param :org-id)
                  (leaf "/topics" :org-topics))))))
