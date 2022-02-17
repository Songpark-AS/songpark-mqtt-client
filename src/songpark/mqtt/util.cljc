(ns songpark.mqtt.util)

(defn broadcast-topic [id]
  (str id "/broadcast"))
