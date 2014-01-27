# bidi

> "bidi bidi bidi" -- Twiki, in probably every episode of
  [Buck Rogers in the 25th Century](http://en.wikipedia.org/wiki/Buck_Rogers_in_the_25th_Century_%28TV_series%29)

In the grand tradition of Clojure libraries we begin with an irrelevant
quote.

Bi-directional URI dispatch. Like
[Compojure](https://github.com/weavejester/compojure), but when you want
to go both ways. If you are serving REST resources, you should be
[providing links](http://en.wikipedia.org/wiki/HATEOAS) to other
resources, and without full support for forming URIs from handlers
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

## Comparison with other routing libraries

There are numerous Clojure routing libraries. Here's a table to help you compare.

<table>
<thead>
<tr>
<th>Library</th>
<th><a href="https://github.com/weavejester/compojure">Compojure</a></th>
<th><a href="https://github.com/cgrand/moustache">Moustache</a></th>
<th><a href="https://github.com/clojurewerkz/route-one">RouteOne</a></th>
<th><a href="http://pedestal.io/">Pedestal</a></th>
<th><a href="https://github.com/thatismatt/gudu">gudu</a></th>
<th>bidi</th>
</tr>
</tr>
</thead>
<tbody>
<tr>
<td>Route syntax</td>
<td>Macros</td>
<td>Macro</td>
<td>Macros</td>
<td>Data literals</td>
<td>Data literals</td>
<td>Data literals</td>
</tr>
<tr>
<td>Bidirectional?</td>
<td>No</td>
<td>No</td>
<td>Yes</td>
<td>Yes</td>
<td>Yes</td>
<td>Yes</td>
</tr>
<tr>
<td>Independent library?</td>
<td>Yes</td>
<td>Yes</td>
<td>Yes</td>
<td>No :-(</td>
<td>Yes</td>
<td>Yes</td>
</tr>
<tr>
<td>Protocol extensibility?</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>Yes</td>
</tr>
<tr>
<td>Name begins with a B?</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>No</td>
<td>Yes!</td>
</tr>
</tbody>
</table>

bidi is written to do
['one thing well'](http://en.wikipedia.org/wiki/Unix_philosophy) (URI
formation and dispatch) and is intended for use with Ring middleware,
HTTP servers (including Jetty, [http-kit](http://http-kit.org/) and
[aleph](https://github.com/ztellman/aleph)) and is fully compatible with
[Liberator](http://clojure-liberator.github.io/liberator/).

## Installation

Add the following dependency to your `project.clj` file

[![Current Version](https://clojars.org/bidi/latest-version.svg)](https://clojars.org/bidi)

## Take 5 minutes to learn bidi (using the REPL)

Let's create a route that matches `/index.html`. A route is simply a
pair, containing a pattern and a result.

```clojure
user> (def route ["/index.html" :index])
#'user/route
```

Let's try to match that route to a path.

```clojure
user> (use 'bidi.bidi)
nil
user> (match-route route "/index.html")
{:handler :index}
```

We have a match! A map is returned with a single entry with a `:handler`
key and `:index` as the value. We could use this result, for example, to
look up a Ring handler in a map mapping keywords to Ring handlers.

What happens if we try a different path?

```clojure
user> (match-route route "/another.html")
nil
```

We get a `nil`. Nil means 'no route matched'.

Now, let's go in the other direction.

```clojure
user> (path-for route :index)
"/index.html"
```

We ask bidi to use the same route definition to tell us the path that
would match the `:index` hander. In this case, it tells us
`/index.html`. So if you were forming a link to this handler from
another page, you could use this function in your view logic to create
the link instead of hardcoding in the view template (This gives your
code more resiliance to changes in the organisation of routes during
development).

### Multiple routes

Now let's suppose we have 2 routes. We match partially on their common
prefix, which in this case is `"/"` but we could use `""` if there were
no common prefix. The patterns for the remaining path can be specified
in a map (or vector of pairs, if order is important).

```clojure
user> (def routes ["/" {"index.html" :index
                        "article.html" :article}])
#'user/routes
```

Since each entry in the map is itself a route, you can nest these
recursively.

```clojure
user> (def routes ["/" {"index.html" :index
                        "articles/" {"index.html" :article-index
                                     "article.html" :article}}])
#'user/routes
```

We can match these routes as before :-

```clojure
user> (match-route routes "/index.html")
{:handler :index}
user> (match-route routes "/articles/article.html")
{:handler :article}
```

and in reverse too :-

```clojure
user> (path-for routes :article-index)
"/articles/index.html"
```

### Route patterns

It's common to want to match on a pattern or template, extracting some
variable from the URI. Rather than including special characters in
strings, we construct the pattern in segments using a Clojure vector
`[:id "/article.html"]`. This vector replaces the string we had in the
left hand side of the route pair.

```clojure
user> (def routes ["/" {"index.html" :index
                        "articles/" {"index.html" :article-index
                                     [:id "/article.html"] :article}}])
#'user/routes
```

Now, when we match on an article path, the keyword values are extracted into a map.

```clojure
user> (match-route routes "/articles/123/article.html")
{:handler :article, :params {:id "123"}}
user> (match-route routes "/articles/999/article.html")
{:handler :article, :params {:id "999"}}
```

To form the path we need to supply the value of `:id` as extra
arguments to the `path-for` function.

```clojure
user> (path-for routes :article :id 123)
"/articles/123/article.html"
user> (path-for routes :article :id 999)
"/articles/999/article.html"
```

If you don't specify a required parameter an exception is thrown.

Apart from a few extra bells and whistles documented in the rest of this
README, that's basically it. Your five minutes are up!

## Wrapping as a Ring handler

Match results can be any value, but are typically functions (either
in-line or via a symbol reference). You can easily wrap your routes to
form a Ring handler (similar to what Compojure's `routes` and
`defroutes` does) with the `make-handler` function.

```clojure
(require '[bidi.bidi :refer (make-handler)])

(def handler
  (make-handler ["/" {"index.html" :index
                      ["articles/" :id "/article.html"] :article}]))
```

## Regular Expressions

We've already seen how keywords can be used to extract segments from a path. By default, keywords capture anything that isn't a forward slash. If you want it to capture something else you can replace the keyword with a pair, containing a regular expression and the keyword.

For example, these 2 patterns are equivalent :-

```clojure
    [ [ "foo/"            :id   "/bar ] :handler ]
    [ [ "foo/" [ #"[^/]+" :id ] "/bar ] :handler ]
```

Both would match strings `foo/123/bar` and `foo/abc/bar`.

But if we wanted `:id` to match only a string of one or more numbers, we could do this :-

```clojure
    [ [ "foo/" [ #"\d+" :id ] "/bar ] :handler ]
```

which would match the string `foo/123/bar` but not `foo/abc/bar`.

## Guards

By default, routes ignore the request method, behaving like Compojure's
`ANY` routes. That's fine if your handlers deal with the request methods
themselves, as
[Liberator](http://clojure-liberator.github.io/liberator/)'s
do. However, if you want to limit a route to a request method, you can
wrap the route in a pair (or map entry), using a keyword for the
pattern. The keyword denotes the request method (`:get`, `:put`, etc.)

```clojure
["/" {"blog" {:get {"/index" (fn [req] {:status 200 :body "Index"})}}}]
```

You can also restrict routes by any other request criteria. Guards are
specified by maps. Map entries can specify a single value, a set of
possible values or even a predicate to test a value.

In this example, the `/zip` route is only matched if the server name in
the request is `juxt.pro`. You can use this feature to restrict routes
to virtual hosts or HTTP schemes.

```clojure
["/" {"blog" {:get
                {"/index" (fn [req] {:status 200 :body "Index"})}}
              {:request-method :post :server-name "juxt.pro"}
                {"/zip" (fn [req] {:status 201 :body "Created"})}}]
```

Values in the guard map can be values, sets of acceptable values, or
even predicate functions to give fine-grained control over the dispatch
criteria.

## Route definitions

A route is formed as a pair: [ *&lt;pattern&gt;* *&lt;matched&gt;* ]

The left-hand-side of a pair is the pattern. It can match a path, either
fully or partially. The simplest pattern is a string, but other types of
patterns are also possible, including segmented paths, regular
expressions, records, in various combinations.

The right-hand-side indicates the result of the match (in the case that
the pattern is matched fully) or a route sub-structure that attempts to
match on the remainder of the path (in the case that the pattern is
matched partially). The route structure is a recursive structure.

This [BNF](http://en.wikipedia.org/wiki/Backus%E2%80%93Naur_Form)
grammar formally defines the basic route structure, although it is
possible extend these definitions by adding types that satisfy the
protocols used in bidi (more on this later).

```
RouteStructure := RoutePair

RoutePair ::= [ Pattern Matched ]

Pattern ::= Path | [ PatternSegment+ ] | MethodGuard | GeneralGuard

MethodGuard ::= :get :post :put :delete :head :options

GeneralGuard ::= [ GuardKey GuardValue ]* (a map)

GuardKey ::= Keyword

GuardValue ::= Value | Set | Function

Path ::= String

PatternSegment ::= String | Regex | Keyword | [ (String | Regex) Keyword ]

Matched ::= Function | Symbol | Keyword | [ RoutePair+ ] { RoutePair+ }
```

In case of confusion, refer to bidi examples found in this README and in
the test suite.

## Composeability

As they are simply nested data structures (strings, vectors, maps),
route structures are highly composeable. They are consistent and easy to
generate. A future version of bidi may contain macros to reduce the
number of brackets needed to create route structures by hand.

## Extensibility

The implementation is based on Clojure protocols which allows the route
syntax to be extended outside of this library.

Built-in records are available but you can also create your own. Below
is a description of the built-in ones and should give you an idea what
is possible. If you add your own types, please consider contributing
them to the project. Make sure you test that your types in both
directions (for URI matching and formation).

### Redirect

The `Redirect` record is included which satisfies the `Matched` protocol.

Consider the following route definition.

```clojure
(defn my-handler [req] {:status 200 :body "Hello World!"})

["/articles" {"/new" my-handler
              "/old" (->Redirect 307 my-handler)}]
```

Any requests to `/articles/old` yield
[*307 Temporary Redirect*](http://en.wikipedia.org/wiki/HTTP_307#3xx_Redirection)
responses with a *Location* header of `/articles/new`. This is a robust
way of forming redirects in your code, since it guarantees that the
*Location URI* matches an existing handler, both reducing the chance of
broken links and encouraging the practise of retaining old URIs (linking
to new ones) after refactoring. You can also use it for the common
practice of adding a *welcome page* suffix, for example, adding
`index.html` to a URI ending in `/`.

### WrapMiddleware

You can wrap the resulting handler in Ring middleware as usual. But
sometimes you need to specify that the handlers from certain patterns
are wrapped in particular middleware.

For example :-

```clojure
(match-route ["/index.html" (->WrapMiddleware handler wrap-params)]
             "/index.html")
```

### Alternates

Sometimes you want to specify a list of potential candidate patterns,
which each match the handler. The first in the list is considered the
canonical pattern for the purposes of URI formation.

```clojure
[(->Alternates ["/index.html" "/index"]) :index]
```

Any pattern can be used in the list. This allows quite sophisticated
matching. For example, if you want to match on requests that are either
HEAD or GET but not anything else.

```clojure
[(->Alternates [:head :get]) :index]
```

Or match if the server name is `juxt.pro` or `localhost`.

```clojure
[(->Alternates [{:server-name "juxt.pro"}{:server-name "localhost"}])
 {"/index.html" :index}]
```

## Performance

Route matching in Compojure is very fast, due to the fact that Compojure
can compile regular-expressions in the reader. By default, bidi
performance is 3-4 times slower. However, a route structure can undergo
a one-time compilation step which prepares the regular expressions and
replaces terms of the route structure with records that have the same
behaviour but higher performance.

```clojure
(def routes ["/" {"index.html" :index
                  "article.html" :article}])

(def compiled-routes (compile-route routes))
```

Since compiled route structures are more unwieldy, the decision of
whether and when to compile a route structure is left to the library
user (you). For example, it is a good idea to serialize route structures
in their uncompiled forms and compile just-in-time prior to the route
structure being used for route matching.

There is a test (`bidi.perf-test`) which demonstrates route
compilation. When using this feature, performance of bidi reaches
near-parity with that of Compojure.

```
Time for 1000 matches using Compojure routes
"Elapsed time: 17.336491 msecs"
Time for 1000 matches using uncompiled bidi routes
"Elapsed time: 66.579074 msecs"
Time for 1000 matches using compiled bidi routes
"Elapsed time: 21.111658 msecs"
```

## Contributing

We welcome pull requests. If possible, please run the tests and make
sure they pass before you submit one.

```
$ lein test

lein test bidi.bidi-test

lein test bidi.perf-test
Time for 1000 matches using Compojure routes
"Elapsed time: 17.645077 msecs"
Time for 1000 matches using uncompiled bidi routes
"Elapsed time: 66.449164 msecs"
Time for 1000 matches using compiled bidi routes
"Elapsed time: 21.269446 msecs"

Ran 9 tests containing 47 assertions.
0 failures, 0 errors.
```

## License

Copyright © 2013, JUXT LTD. All Rights Reserved.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
