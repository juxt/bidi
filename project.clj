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

(defproject bidi "1.11.0"
  :description "Bidirectional URI routing"
  :url "https://github.com/juxt/bidi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.match "0.2.1"]
                 [com.cemerick/url "0.1.1"]
                 [ring/ring-core "1.2.1"]]

  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]
                                  [compojure "1.1.6"]]

                   :plugins [[com.keminglabs/cljx "0.4.0"]
                             [lein-cljsbuild "1.0.3"]
                             [com.cemerick/clojurescript.test "0.3.1"]]

                   :hooks [cljx.hooks]

                   :cljx {:builds [{:source-paths ["src"]
                                    :output-path "target/generated/src/clj"
                                    :rules :clj}
                                   {:source-paths ["src"]
                                    :output-path "target/generated/src/cljs"
                                    :rules :cljs}
                                   {:source-paths ["test"]
                                    :output-path "target/generated/test/clj"
                                    :rules :clj}
                                   {:source-paths ["test"]
                                    :output-path "target/generated/test/cljs"
                                    :rules :cljs}]}}}

  :aliases {"deploy" ["do" "clean," "cljx" "once," "deploy" "clojars"]
            "test" ["do" "clean," "cljx" "once," "test," "with-profile" "dev" "cljsbuild" "test"]}

  :jar-exclusions [#"\.cljx|\.swp|\.swo|\.DS_Store"]

  :lein-release {:deploy-via :shell
                 :shell ["lein" "deploy"]}

  :source-paths ["target/generated/src/clj" "src"]

  :resource-paths ["target/generated/src/cljs"]

  :test-paths ["target/generated/test/clj" "test"]

  :cljsbuild {:test-commands {"unit" ["phantomjs" :runner
                                      "window.literal_js_was_evaluated=true"
                                      "target/unit-test.js"]}
              :builds
              {:test {:source-paths ["src" "test"
                                     "target/generated/src/cljs"
                                     "target/generated/test/cljs"]
                      :compiler {:output-to "target/unit-test.js"
                                 :optimizations :whitespace}}}})
