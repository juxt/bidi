(ns bidi.router
  (:require [bidi.bidi :as bidi]
            [goog.History :as h]
            [goog.events :as e]
            [clojure.string :as s])
  (:import [goog History]))

(defprotocol Router
  (set-location! [_ location])
  (replace-location! [_ location]))

(defn start-router!
  "Starts up a Bidi router based on Google Closure's 'History'

  Types:

    Location :- {:handler ...
                 :route-params {...}}

  Parameters:

    routes :- a Bidi route structure
    on-navigate :- 0-arg function, accepting a Location
    default-location :- Location to default to if the current token doesn't match a route

  Returns :- Router

  Example usage:

    (require '[bidi.router :as br])

    (let [!location (atom nil)
          router (br/start-router! [\"\" {\"/\" ::home-page
                                          \"/page2\" ::page2}]
                                   {:on-navigate (fn [location]
                                                   (reset! !location location))
                                    :default-location {:handler ::home-page}})]

      ...

      (br/set-location! router {:handler ::page2}))"

  [routes {:keys [on-navigate default-location]
           :or {on-navigate (constantly nil)}}]

  (let [history (History.)]
    (.setEnabled history true)

    (letfn [(token->location [token]
              (or (bidi/match-route routes token)
                  default-location))

            (location->token [{:keys [handler route-params]}]
              (bidi/unmatch-pair routes {:handler handler
                                         :params route-params}))]

      (e/listen history h/EventType.NAVIGATE
                (fn [e]
                  (on-navigate (token->location (.-token e)))))

      (let [initial-token (let [token (.getToken history)]
                            (if-not (s/blank? token)
                              token
                              (or (location->token default-location) "/")))

            initial-location (token->location initial-token)]

        (.replaceToken history initial-token)
        (on-navigate initial-location))

      (reify Router
        (set-location! [_ location]
          (.setToken history (location->token location)))

        (replace-location! [_ location]
          (.replaceToken history (location->token location)))))))
