# bidi

> "bidi bidi bidi" -- Twiki, in probably every episode of
  [Buck Rogers in the 25th Century](http://en.wikipedia.org/wiki/Buck_Rogers_in_the_25th_Century_%28TV_series%29)

In the grand tradition of Clojure libraries we begin with an irrelevant
quote.

Bi-directional URI routing between handlers and routes. Like
[Compojure](https://github.com/weavejester/compojure), but when you want
to go both ways. For example, many routing libraries can route a URI to
a request handler, but only a fraction of these (for example,
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
macro-based DSLs make harder. This project also avoids 'terse' forms for
the route definitions, it is better to learn and live with a single data
structure.

The logic for matching routes is separated from the responsibility for
handling requests. This is an important
[architectural principle](http://www.infoq.com/presentations/Simple-Made-Easy). So
you can match on things that aren't necessarily handlers, like keywords
which you can use to lookup your handlers, or whatever you want to
do. Separation of concerns and all that.

## Installation

Add the following dependency to your `project.clj` file

```clojure
[bidi "1.1.0"]
```

## Usage

```clojure
(require '[bidi.bidi :refer (match-route)])

(match-route
    ["/blog" [["/index 'index]
              [["/articles/" :artid "/index"] :article]]]
    "/blog/articles/123/index.html")
```

returns

```clojure
{:handler :article, :params {:artid "123"}, :path ".html"}
```

You can also go in the reverse direction

```clojure
(require '[bidi.bidi :refer (path-for)])

(path-for ["/blog"
            [["/index.html" 'blog-index]
             [["/article/" :id ".html"] 'blog-article-handler]
             [["/archive/" :id "/old.html"] 'foo]]]
          {:handler 'blog-article-handler :params {:id 1239}})
```

returns

```clojure
"/blog/article/1239.html"
```

[Nice!](http://i357.photobucket.com/albums/oo17/MageOfTheOnyx/LouisBalfour.jpg)

You don't have to route to functions, you can use symbols or keywords
too. If you do use functions, however, you can easily create a Ring
handler from your route defintions. You decide what works best for your application.

```clojure
(require '[bidi.bidi :refer (make-handler)])

(def handler
  (make-handler ["/blog"
                 [["/index.html" blog-index]
                  [["/article/" :id ".html"] blog-article-handler]
                  [["/archive/" :id "/old.html"] (fn [req] {:status 404}]]]))
```

By default, routes don't dispatch on the request method and behave like Compojure's `ANY` routes. That's fine if your handlers deal with the request methods themselves, as [Liberator](http://clojure-liberator.github.io/liberator/)'s do. However, you can specify a method using a keyword.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]]]]]
```

You can also restrict routes by other criteria. In this example, the `/zip` route is only matched if the server name in the request is `juxt.pro`. Guards are specified by maps. Map entries can specify a single value, a set of possible values or even a predicate to test a value.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]
    [{:request-method :post :server-name "juxt.pro"} [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
   ]]]
```

## Route definitions

This [BNF](http://en.wikipedia.org/wiki/Backus%E2%80%93Naur_Form)
grammar describes the structure of the routes definition data
structures.

```
RoutesDefinition ::= RoutePair

RoutePair ::= [Matcher RouteSpec]

Matcher ::= MethodGuard | Guard | Path | [ PathComponent+ ]

MethodGuard ::= :get :post :put :delete :head :options

Guard ::= { GuardKey GuardValue }

GuardKey ::= Keyword

GuardValue ::= Value | Set | Function

Path ::= String

PathComponent ::= String | Keyword

RouteSpec ::= Function | Symbol | Keyword | RoutePair | [ RoutePair+ ]
```

The implementation is based on protocols which can be extended by the
user to support other types, for example, integrating regular
expressions.

## License

Copyright © 2013, JUXT LTD. All Rights Reserved.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
