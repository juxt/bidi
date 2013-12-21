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

In bidi, routes are data structures, there are no macros here.

The logic for matching routes is separated from the responsibility for
handling requests. This is an important
[architectural principle](http://www.infoq.com/presentations/Simple-Made-Easy). So
you can match on things that aren't necessarily handlers, like keywords
which you can use to lookup your handlers, or whatever you want to
do. Separation of concerns and all that.

## Usage

```clojure
(require '[bidi.bidi :refer (match-route)])

(match-route
    ["/blog" [["/foo" 'foo]
              [["/bar/articles/" :artid "/index"] 'bar]]]
    "/blog/bar/articles/123/index.html")
```

returns

```clojure
{:handler 'bar, :params {:artid "123"}, :path ".html"}
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

## Route definitions

A simple [BNF](http://en.wikipedia.org/wiki/Backus%E2%80%93Naur_Form)
grammar describes the structure of the routes definition data
structures.

```
RoutesDefinition ::= RoutePair

RoutePair ::= [Matcher RouteSpec]

Matcher ::= Path | [ PathComponent+ ]

Path ::= String

PathComponent ::= String | Keyword

RouteSpec ::= Symbol | Keyword | RoutePair | [ RoutePair+ ]
```

The implementation is based on protocols which can be extended by the
user to support other types, for example, integrating regular
expressions.

## License

Copyright © 2013, JUXT LTD. All Rights Reserved.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
