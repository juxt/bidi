;; Copyright © 2014, JUXT LTD.

(defproject bidi "1.21.0-SNAPSHOT"
  :description "Bidirectional URI routing"
  :url "https://github.com/juxt/bidi"

  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :dependencies [[prismatic/schema "0.4.3"]
                 [com.cemerick/url "0.1.1"]
                 [ring/ring-core "1.3.2" :exclusions [org.clojure/tools.reader]]]

  :plugins [[lein-cljsbuild "1.0.3"]
            [com.cemerick/clojurescript.test "0.3.1"]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "0.0-2850"]
                                  [ring-mock "0.1.5"]
                                  [compojure "1.1.6"]
                                  [criterium "0.4.3"]]}}

  ;;:aliases {"test" ["do" "test," "with-profile" "dev" "cljsbuild" "test"]}

  :jar-exclusions [#"\.cljx|\.swp|\.swo|\.DS_Store"]

  :lein-release {:deploy-via :shell
                 :shell ["lein" "deploy"]}

  :source-paths ["src"]

  :resource-paths []

  :test-paths ["test"]

  :cljsbuild {:test-commands {"unit" ["phantomjs" :runner
                                      "window.literal_js_was_evaluated=true"
                                      "target/unit-test.js"]}
              :builds
              {:test {:source-paths ["src" "test"]
                      :compiler {:output-to "target/unit-test.js"
                                 :optimizations :whitespace}}}})
