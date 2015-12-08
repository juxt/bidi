;; Copyright © 2014-2015, JUXT LTD.

(ns bidi.schema
  (:require
    #?(:clj [schema.core :as s]
       :cljs [schema.core :as s :include-macros true])))

(def Path s/Str)

(def PatternSegment
  (s/cond-pre s/Str
              s/Regex
              s/Keyword
              (s/pair (s/cond-pre s/Str s/Regex) "qual" s/Keyword "id")))

(def MethodGuard
  (s/enum :get :post :put :delete :head :options))

(def GeneralGuard
  {s/Keyword (s/cond-pre s/Str s/Keyword (s/=> s/Any s/Any))})

(def Pattern
  (s/cond-pre Path
              [PatternSegment]
              MethodGuard
              GeneralGuard
              s/Bool))

(declare ^:export RoutePair)

(def Matched
  (s/cond-pre s/Symbol
              s/Keyword
              [(s/recursive #'RoutePair)]
              {Pattern (s/recursive #'Matched)}
              (s/=> s/Any s/Any)))

(def ^:export RoutePair (s/pair Pattern "" Matched ""))
