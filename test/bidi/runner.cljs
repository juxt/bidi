;; Copyright © 2014, JUXT LTD.

(ns bidi.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [bidi.bidi-test]
            [bidi.schema-test]))

(doo-tests 'bidi.bidi-test
           'bidi.schema-test)
