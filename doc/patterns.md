# bidi Patterns

Here are some patterns that you may find useful in your development.

## Handler map promise

### Situation

You have a bunch of REST resources, each of which might need to form
hyperlinks to one or more of the others. There may even be a mutual
dependency. Which handler do you create first?

### Solution

Use a promise to access a handler map, containing entries for each
handler keyed with keywords. The promise must be delivered prior to
calling dereferencing it in a call to `path-for`. This is ensured by the
`make-handlers` function, which delivers the promise prior to returning
the result, so the promise cannot escape the handler construction phase.

### Discussion

Here is an example which demonstrates the technique. It uses a pair of
[Liberator](http://clojure-liberator.github.io/liberator/) resources to
create a REST API, which need to create
[hyperlinks](http://en.wikipedia.org/wiki/HATEOAS) to each other.

In the code below we assume that the bidi routes are available in the
request, under the `:request` key. Every application is different, and its
up to the bidi user to ensure that request handlers have access to the
overall route structure.

Notice how both resources use the `path-for` function to form paths to
the other resource.

The `make-handlers` function creates a promise to a map containing each
handler, referenced by a known keyword, which can be used to look up the
handler, and thereby form the path to it.

```clojure
(defresource contacts [database handlers]
  :allowed-methods #{:post}
  :post! (fn [{{body :body} :request}]
           {:id (create-contact! database body)})
  :handle-created (fn [{{routes :routes} :request id :id}]
                    (assert (realized? handlers))
                    (ring-response
                     {:headers {"Location" (path-for routes (:contact @handlers) :id id)}})))

(defresource contact [handlers]
  :allowed-methods #{:delete :put}
  :available-media-types #{"application/json"}
  :handle-ok (fn [{{{id :id} :route-params routes :routes} :request}]
               (assert (realized? handlers))
               (html [:div [:h2 "Contact: " id]
                      [:a {:href (path-for routes (:contacts @handlers))} "Index"]])))

(defn make-handlers [database]
  (let [p (promise)]
    ;; Deliver the promise so it doesn't escape this function.
    @(deliver p {:contacts (contacts database p)
                 :contact (contact p)})))

(defn make-routes [handlers]
  ["/" [["contacts" (:contacts handlers)]
       [["contact/" :id] (:contact handlers)]
       ]])

;; Create the route structure like this :-

(-> database make-handlers make-routes)

```
