(ns songpark.mqtt.util)

(defn broadcast-topic [id]
  (str id "/broadcast"))

(defn app-topic [id]
  (str id "/app"))

(defn teleporter-topic [id]
  (str id "/teleporter"))
