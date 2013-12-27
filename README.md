# bidi

> "bidi bidi bidi" -- Twiki, in probably every episode of
  [Buck Rogers in the 25th Century](http://en.wikipedia.org/wiki/Buck_Rogers_in_the_25th_Century_%28TV_series%29)

In the grand tradition of Clojure libraries we begin with an irrelevant
quote.

Bi-directional URI dispatch. Like
[Compojure](https://github.com/weavejester/compojure), but when you want
to go both ways. For example, many routing libraries can route a URI to
a request handler, but only some of these (for example,
[Pedestal](http://pedestal.io),
[gudu](https://github.com/thatismatt/gudu)) can take a reference to a
handler, plus some environment, and generate a URI. If you are serving
REST resources, you should be
[providing links](http://en.wikipedia.org/wiki/HATEOAS) to other
resources, and without full support for generating URIs from handlers
your code will become coupled with your routing. In short, hard-coded
URIs will eventually break.

In bidi, routes are *data structures*, there are no macros here. Generally
speaking, data structures are to be preferred over code structures. When
routes are defined in a data structure there are numerous
advantages - they can be read in from a configuration file, generated,
computed, transformed by functions and introspected - all things which
macro-based DSLs make harder.

For example, suppose you wanted to use the same set of routes in your
application and in your production [Nginx](http://wiki.nginx.org/Main)
or [HAProxy](http://haproxy.1wt.eu/) configuration. Having your routes
defined in a single data structure means you can programmatically
generate your configuration, making your environments easier to manage
and reducing the chance of discrepancies.

bidi also avoids 'terse' forms for the route definitions- reducing the
number of parsing rules for the data structure is valued over
convenience for the programmer. Convenience can always be added later
with macros.

Finally, the logic for matching routes is separated from the
responsibility for handling requests. This is an important
[architectural principle](http://www.infoq.com/presentations/Simple-Made-Easy). So
you can match on things that aren't necessarily handlers, like keywords
which you can use to lookup your handlers, or whatever you want to
do. Separation of concerns and all that.

## Installation

Add the following dependency to your `project.clj` file

```clojure
[bidi "1.4.0"]
```

## Take 5 minutes to learn bidi (using the REPL)

Let's create a route that matches `/index.html`. All routes are pairs: [ *&lt;pattern&gt;* *&lt;matched&gt;* ]

```clojure
    user> (def route ["/index.html" :index])
    #'user/route
```

Let's try to match that route to a path.

```clojure
user> (use 'bidi.bidi)
nil
user> (match-route "/index.html" route)
{:handler :index}
```

We have a match! A map is returned with a single entry with a `:handler`
key and `:index` as the value. We could use this result, for example, to
look up a Ring handler in a map mapping keywords to Ring handlers.

What happens if we try a different path?

```clojure
user> (match-route "/another.html" route)
nil
```

We get a `nil`. Nil means 'no route matched'.

Now, let's go in the other direction.

```clojure
user> (path-for :index route)
"/index.html"
```

We ask bidi to use the same route definition to tell us the path that
would match the `:index` hander. In this case, it tells us
`/index.html`. So if you were generating a link to this handler from
another page, you could use this function in your view logic to create
the link instead of hardcoding in the view template (This gives your
code more resiliance to changes in the organisation of routes during
development).

### Mutliple routes

Now let's suppose we have 2 routes. We match on their common prefix (or
use `""` if necessary) and the 2 routes are contained in a vector.

```clojure
    user> (def routes ["/" [
            ["index.html" :index]
            ["article.html" :article]
           ]])
    #'user/routes
```

Since each element in the vector is itself a route, you can nest these
recursively.

```clojure
    user> (def routes ["/" [
            ["index.html" :index]
            ["articles/" [
              ["index.html" :article-index]
              ["article.html" :article]
            ]]
           ]])
    #'user/routes
```

We can match these routes as before :-

```clojure
user> (match-route "/index.html" routes)
{:handler :index}
user> (match-route "/articles/article.html" routes)
{:handler :article}
```

and in reverse too :-

```clojure
user> (path-for :article-index routes)
"/articles/index.html"
```

### Route patterns

It's common to want to match on a pattern or template, extracting some
variable from the URI. Rather than including special characters in
strings, we construct the pattern in segments using a Clojure vector
`[:id "/article.html"]`. This vector replaces the string we had in the
left hand side of the route pair.

```clojure
    user> (def routes ["/" [
            ["index.html" :index]
            ["articles/" [
              ["index.html" :article-index]
              [[:id "/article.html"] :article]
            ]]
           ]])
    #'user/routes
```

Now, when we match on an article path, the keyword values are extracted into a map.

```clojure
user> (match-route "/articles/123/article.html" routes)
{:handler :article, :params {:id "123"}}
user> (match-route "/articles/999/article.html" routes)
{:handler :article, :params {:id "999"}}
```

To generate the path we need to supply the value of `:id` as extra
arguments to the `path-for` function.

```clojure
user> (path-for :article routes :id 123)
"/articles/123/article.html"
user> (path-for :article routes :id 999)
"/articles/999/article.html"
```

If you don't specify a required parameter an exception is thrown.

Apart from a few extra bells and whistles documented in the rest of this
README, that's basically it. Your five minutes are up!

## Wrapping as a Ring handler

You can use functions (or symbols that represent them) as pattern
targets. If you choose to do this a benefit it that you can wrap your
routes to form a Ring handler (similar to what Compojure's `routes` and
`defroutes` does).

```clojure
(require '[bidi.bidi :refer (make-handler)])

(def handler
  (make-handler ["/" [
                      ["index.html" :index]
                      [["articles/" :id "/article.html"] :article]
                     ]])
```

## Guards

### Method guards

By default, routes don't dispatch on the request method and behave like
Compojure's `ANY` routes. That's fine if your handlers deal with the
request methods themselves, as
[Liberator](http://clojure-liberator.github.io/liberator/)'s
do. However, you can specify a method by wrapping a route (or routes) in
a pair, where the first element is a keyword denoting the methods.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]]]]]
```

### Other guards

You can also restrict routes by other criteria. In this example, the
`/zip` route is only matched if the server name in the request is
`juxt.pro`. Guards are specified by maps. Map entries can specify a
single value, a set of possible values or even a predicate to test a
value.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]
    [{:request-method :post :server-name "juxt.pro"}
     [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
   ]]]
```

## Route definitions

This [BNF](http://en.wikipedia.org/wiki/Backus%E2%80%93Naur_Form)
grammar describes the structure of the routes definition data
structures.

```
RoutePair ::= [Pattern Matched]

Pattern ::= Path | [ PatternSegment+ ] | MethodGuard | GeneralGuard

MethodGuard ::= :get :post :put :delete :head :options

GeneralGuard ::= [ GuardKey GuardValue ]* (a map)

GuardKey ::= Keyword

GuardValue ::= Value | Set | Function

Path ::= String

PatternSegment ::= String | Regex | Keyword | [ (String | Regex) Keyword ]

Matched ::= Function | Symbol | Keyword | [ RoutePair+ ]
```

## Composeability

Route structures are composeable. They are consistent and easy to
generate. A future version of bidi may contain macros to reduce the
number of parenthesis needed to create route structures by hand.

## Extensibility

The implementation is based on Clojure protocols which allows the route
syntax to be extended outside of this library.

An example `Redirect` record is included which satisfies the `Matched` protocol.

Consider the following route definition.

```clojure
    (defn my-handler [req] {:status 200 :body "Hello World!"})

    ["/articles"
      [
        ["/new" my-handler]
        ["/old" (bidi.bidi.Redirect. 307 my-handler)]]]
```

Any requests to `/articles/old` yield
[*307 Temporary Redirect*](http://en.wikipedia.org/wiki/HTTP_307#3xx_Redirection)
responses with a *Location* header of `/articles/new`. This is a robust
way of forming redirects in your code, since it guarantees that the
*Location URI* matches an existing handler, both reducing the chance of
broken links and encouraging the practise of retaining old URIs (linking
to new ones) after refactoring. You can also use it for the common practice of adding a *welcome page* suffix, for example, adding `index.html` to a URI ending in `/`.

## License

Copyright © 2013, JUXT LTD. All Rights Reserved.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
