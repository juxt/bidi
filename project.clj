;; Copyright © 2014, JUXT LTD.

(defproject bidi "1.21.1"
  :description "Bidirectional URI routing"
  :url "https://github.com/juxt/bidi"

  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :dependencies [[prismatic/schema "1.0.1"]
                 [com.cemerick/url "0.1.1"]
                 [ring/ring-core "1.4.0"]]

  :plugins [[lein-cljsbuild "1.0.3"]
            [com.cemerick/clojurescript.test "0.3.1"]]

  :prep-tasks ["javac" "compile"]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.145"]
                                  [ring/ring-mock "0.3.0"]
                                  [compojure "1.4.0"]
                                  [criterium "0.4.3"]]}}

  :aliases {"deploy" ["do" "clean," "deploy" "clojars"]
            "test" ["do" "clean," "test," "with-profile" "dev" "cljsbuild" "test"]}

  :jar-exclusions [#"\.swp|\.swo|\.DS_Store"]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.145"]
                                  [ring/ring-mock "0.3.0"]
                                  [compojure "1.4.0"]
                                  [criterium "0.4.3"]]}}

  :cljsbuild {:test-commands {"unit" ["phantomjs" :runner
                                      "window.literal_js_was_evaluated=true"
                                      "target/unit-test.js"]}
              :builds
              {:test {:source-paths ["src" "test"]
                      :compiler {:output-to "target/unit-test.js"
                                 :optimizations :whitespace}}}})
