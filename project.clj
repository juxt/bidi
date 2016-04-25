;; Copyright © 2014, JUXT LTD.

(defproject bidi "2.0.8"
  :description "Bidirectional URI routing"
  :url "https://github.com/juxt/bidi"

  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

;;  :pedantic? :abort

  :dependencies [[prismatic/schema "1.0.3"]
                 [ring/ring-core "1.4.0" :exclusions [org.clojure/clojure]]]

  :plugins [[lein-cljsbuild "1.1.1"]
            [lein-doo "0.1.6"]]

  :prep-tasks ["javac" "compile"]

  :profiles {:dev {:exclusions [[org.clojure/tools.reader]]
                   :resource-paths ["test-resources"]
                   :dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/clojurescript "1.7.170"]
                                  [org.clojure/tools.reader "0.10.0-alpha3"]
                                  [ring/ring-mock "0.3.0"]
                                  [compojure "1.4.0"]
                                  [criterium "0.4.3"]
                                  [org.mozilla/rhino "1.7.7"]]}}

  :aliases {"deploy" ["do" "clean," "deploy" "clojars"]
            "test" ["do" "clean," "test," "doo" "rhino" "test" "once"]}

  :jar-exclusions [#"\.swp|\.swo|\.DS_Store"]

  :lein-release {:deploy-via :shell
                 :shell ["lein" "deploy"]}

  :doo {:paths {:rhino "lein run -m org.mozilla.javascript.tools.shell.Main"}}

  :cljsbuild {:builds
              {:test {:source-paths ["src" "test"]
                      :compiler {:output-to "target/unit-test.js"
                                 :main 'bidi.runner
                                 :optimizations :whitespace}}}})
