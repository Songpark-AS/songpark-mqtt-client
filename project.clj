(defproject songpark-mqtt "0.1.0-SNAPSHOT"
  :description "MQTT library for Songpark"

  :dependencies [[org.clojure/clojure "1.10.3"]
                 ;; time
                 [tick "0.5.0-RC1"]
                 ;; core async. used for request/response pattern
                 [org.clojure/core.async "1.5.648"]
                 ;; logging
                 [com.taoensso/timbre "5.1.2" :scope "provided"]
                 ;; clojure mqtt
                 [clojurewerkz/machine_head "1.0.0"]
                 ;; clojurescript mqtt
                 [cljsjs/paho "1.0.3-0"]
                 [songpark/common "0.1.1-SNAPSHOT"]]

  :repl-options {:init-ns songpark-mqtt.core}
  :profiles {:dev {:dependencies [[clj-commons/spyscope "0.1.48"]]
                   :injections [(require 'spyscope.core)]
                   :plugins [[lein-cljsbuild "1.1.8"]
                             [lein-auto "0.1.3"]]}})
