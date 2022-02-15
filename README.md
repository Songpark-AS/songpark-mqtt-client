# songpark-mqtt

MQTT library for Songpark.

Meant for use both for Clojure and Clojurescript.

## Clojure

- Packaged as a jar and is used in the standard way.
- Developed with `lein`
- Tested with `lein auto test`


## Clojurescript

- Packaged as a jar, but needs to be used specifically from shadow-cljs and have the npm packages added manually.
- Developed with `shadow-cljs`
- Tested with `shadow-cljs watch test`
  - Open up the URL given by shadow-cljs
  - Open up the console
  - The tests should appear and automatically reload when you make changes

### Clojurescript tests

Note that the clojurescript tests are a bit flaky. The main reason for this is
the single-threaded nature of javascript, and the test code testing out async
functions that need to wait for things to go over the network. This has been
"solved" by introducing core.async timeouts, which doesn't really solve the
problem, but gives enough time for the network transversal to happen and allow
for the javascript main thread to pick up any code execution from callbacks
before continuing from the parked timeout. This approach sort of works, but we
cannot deterministically tell the execution thread where it picks up the tasks
again.

## Why shadow-cljs?

I tried lots of different combinations, but anything involving lein for
Clojurescript development was kind of a pain. Between clj CLI and shadow-cljs I
am the most familiar with shadow-cljs, and its approach to the NPM eco system
work wonders.

## cljs deps

``` clojure
;; structure
[com.stuartsierra/component "1.0.0"]

;; time
[tick "0.5.0-RC5"]

;; core async. used for request/response pattern
[org.clojure/core.async "1.5.648"]

;; logging
[com.taoensso/timbre "5.1.2"]

;; transit
[com.cognitect/transit-cljs "0.8.269"]]
```

## npm deps

Versions are a bit more fluid compared to the Clojure side due to how the npm
ecosystem works. Take them more as guidelines.

- tick
  - @js-joda/core 3.2.0
  - @js-joda/locale_en-us 3.1.1
  - @js-joda/timezone 2.5.0
- MQTT client
  - paho-mqtt ^1.1.0
