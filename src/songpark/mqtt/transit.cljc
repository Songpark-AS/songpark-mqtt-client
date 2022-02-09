(ns songpark.mqtt.transit
  (:require [cognitect.transit :as transit]
            #?(:cljs [java.time])
            [tick.core :as t]))


(defn time-fn [obj]
  (str obj))

(defn rep [text]
  (fn [& _]
    text))

#? (:cljs (def write-handlers {java.time/Instant (transit/write-handler (rep "time/instant") time-fn)
                               java.time/Month (transit/write-handler (rep "time/month") time-fn)
                               java.time/DayOfWeek (transit/write-handler (rep "time/day-of-week") time-fn)
                               java.time/Year (transit/write-handler (rep "time/year") time-fn)})
    :clj (def write-handlers {java.time.Instant (transit/write-handler (rep "time/instant") time-fn)
                              java.time.Month (transit/write-handler (rep "time/month") time-fn)
                              java.time.DayOfWeek (transit/write-handler (rep "time/day-of-week") time-fn)
                              java.time.Year (transit/write-handler (rep "time/year") time-fn)}))

#?(:clj  (def writer (transit/writer (java.io.ByteArrayOutputStream. 4096) :json {:handlers write-handlers}))
   :cljs (def writer (transit/writer :json {:handlers write-handlers})))

(def read-handlers {"time/instant" (transit/read-handler (fn [obj] (t/instant obj)))
                    "time/month" (transit/read-handler (fn [obj] (t/month obj)))
                    "time/day-of-week" (transit/read-handler (fn [obj] (t/day-of-week obj)))
                    "time/year" (transit/read-handler (fn [obj] (t/year obj)))})


#?(:cljs (def reader (transit/reader :json)))
