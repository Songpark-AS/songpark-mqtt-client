(defproject songpark/mqtt "1.0.5"
  :description "MQTT library for Songpark"

  :dependencies [;; clojure
                 [org.clojure/clojure "1.10.3" :scope "provided"]

                 ;; structure
                 [com.stuartsierra/component "1.0.0" :scope "provided"]

                 ;; time
                 [tick "0.5.0-RC5"]

                 ;; core async. used for request/response pattern
                 [org.clojure/core.async "1.5.648"]

                 ;; logging
                 [com.taoensso/timbre "5.1.2" :scope "provided"]

                 [clojurewerkz/machine_head "1.0.0"]

                 ;; transit
                 [com.cognitect/transit-clj "1.0.324"]
                 [cheshire "5.10.0"]]

  :repl-options {:init-ns songpark.mqtt}

  :plugins [[lein-auto "0.1.3"]]

  :profiles {:dev {:dependencies [[clj-commons/spyscope "0.1.48"]]
                   :injections [(require 'spyscope.core)]}})
