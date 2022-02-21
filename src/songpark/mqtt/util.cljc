(ns songpark.mqtt.util)

(defn broadcast-topic [id]
  (str id "/broadcast"))

(defn app-topic [id]
  (str id "/app"))

(defn teleporter-topic [id]
  (str id "/teleporter"))

(defn heartbeat-topic
  "For listening on the heartbeat of a Teleporter"
  [id]
  (str id "/heartbeat"))
