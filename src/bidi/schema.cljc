;; Copyright © 2014-2015, JUXT LTD.

(ns bidi.schema
  (:require
   [bidi.bidi :as bidi]
    #?(:clj [schema.core :as s]
       :cljs [schema.core :as s :include-macros true])))

(def Path s/Str)

(defn valid-qualifier-function? [qual]
  (contains? #{keyword long bidi/uuid} qual))

(def PatternSegment
  (s/cond-pre s/Str
              s/Regex
              s/Keyword
              (s/pair (s/conditional #(fn? %) (s/pred valid-qualifier-function?) :else s/Regex) "qual" s/Keyword "id")))

(def MethodGuard
  (s/enum :get :post :put :patch :delete :head :options))

(def GeneralGuard
  {s/Keyword (s/cond-pre s/Str s/Keyword (s/=> s/Any s/Any))})

(s/defschema SegmentedPattern
  (s/constrained [PatternSegment] not-empty 'not-empty))

(declare Pattern)

(s/defschema AlternatesSet
  (s/constrained #{(s/recursive #'Pattern)} not-empty 'not-empty))

(s/defschema DeprecatedAlternates
  (s/record bidi.bidi.Alternates
            {:alts [(s/recursive #'Pattern)]}))

(def Pattern
  (s/cond-pre AlternatesSet #_(s/protocol bidi/Pattern)
              DeprecatedAlternates
              Path
              SegmentedPattern
              MethodGuard
              GeneralGuard
              s/Bool))

(declare ^:export RoutePair)

(def Matched
  (s/cond-pre
   (s/pred record?)
   s/Symbol
   s/Keyword
   [(s/recursive #'RoutePair)]
   {Pattern (s/recursive #'Matched)}
   (s/=> s/Any s/Any)
   ))

(def ^:export RoutePair (s/pair Pattern "Pattern" Matched "Matched"))
