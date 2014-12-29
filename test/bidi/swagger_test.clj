;; Copyright © 2014 JUXT LTD.

(ns bidi.swagger-test
  (:require
   [bidi.swagger :refer (match-route map->Resource)]
   [bidi.bidi :refer (path-for)]
   [clojure.test :refer :all]
   [ring.mock.request :refer :all]
   [clojure.data.json :as json]
   [schema.core :as s]))

(def routes
  ["/a"
   [["/pets"
     [[""
       (map->Resource
        {:get
         {:description "Returns all pets from the system that the user has access to"
          :operationId :findPets
          :produces ["application/json" "application/xml" "text/xml" "text/html"]
          :parameters [{:name "tags" :in :query :description "tags to filter by"
                        :required false
                        :type :array
                        :items {:type :string}
                        :collectionFormat :csv}
                       {:name "limit"
                        :in :query
                        :description "maximum number of results to return"
                        :required false
                        :type :integer
                        :format :int32}]
          :responses {200 {:description "pet.repsonse"
                           :schema []}}}

         :post
         {:description "Creates a new pet in the store.  Duplicates are allowed"
          :operationId :addPet}})]

      [["/" :id]
       (map->Resource
        {:get
         {:description "Returns a user based on a single ID, if the user does not have access to the pet"
          :operationId :findPetById}})]]]]])

(deftest match-route-test
  (let [res (match-route routes "/a/pets")]
    (is (= :findPets (get-in res [:bidi.swagger/resource :get :operationId])))))

(deftest path-for-test
  (is (= (path-for routes :findPets) "/a/pets"))
  (is (= (path-for routes :findPetById :id 200) "/a/pets/200")))

(deftest swagger-spec-test
  (let [spec (swagger-spec
              :info {:version "1.0.0"
                     :title "Swagger Petstore"}
              :paths (swagger-paths routes))]
;;    (clojure.pprint/pprint spec)
    (is (= (:swagger spec) "2.0"))
    (is (= (get-in spec [:paths "/a/pets" :get :operationId]) :findPets))
    (is (= (get-in spec [:paths "/a/pets" :post :operationId]) :addPet))
    (is (= (get-in spec [:paths "/a/pets/{id}" :get :operationId]) :findPetById))
    (println (json/write-str spec))
    (is (>= (count (json/write-str spec)) 500))))
