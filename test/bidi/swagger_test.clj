;; Copyright © 2014 JUXT LTD.

(ns bidi.swagger-test
  (:require
   [bidi.ring :refer :all]
   [bidi.bidi :refer :all]
   [bidi.swagger :refer :all]
   [clojure.test :refer :all]
   [ring.mock.request :refer :all]
   [clojure.data.json :as json]))

(def routes
  ["/a"
   [["/pets"
     [[""
       {:get
        #bidi.swagger/op
        {:description "Returns all pets from the system that the user has access to"
         :operationId :findPets}

        :post
        #bidi.swagger/op
        {:description "Creates a new pet in the store.  Duplicates are allowed"
         :operationId :addPet}}]

      [["/" :id]
       {:get
        #bidi.swagger/op
        {:description "Returns a user based on a single ID, if the user does not have access to the pet"
         :operationId :findPetById}}]]]]])

(deftest match-route-test
  (let [res (match-route routes "/a/pets" :request-method :get)]
    (is (= :findPets (get-in res [:swagger/op :operationId])))))

(deftest path-for-test
  (is (= (path-for routes :findPets) "/a/pets"))
  (is (= (path-for routes :findPetById :id 200) "/a/pets/200")))

(deftest swagger-spec-test
  (let [spec (swagger-spec routes)]
    (is (= (:swagger spec) "2.0"))
    (is (= (get-in spec [:paths "/a/pets" :get :operationId]) :findPets))
    (is (= (get-in spec [:paths "/a/pets" :post :operationId]) :addPet))
    (is (= (get-in spec [:paths "/a/pets/{id}" :get :operationId]) :findPetById))))
