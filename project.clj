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

(defproject bidi "1.10.4"
  :description "Bidirectional URI routing"
  :url "https://github.com/juxt/bidi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.match "0.2.0"]
                 [ring/ring-core "1.2.1"]]
  :lein-release {:deploy-via :clojars}
  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]
                                  [compojure "1.1.6"]]}})
