(ns songpark.mqtt
  (:require [clojurewerkz.machine-head.client :as mh]
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as component]
            [songpark.common.communication :refer [write-handlers]]
            [taoensso.timbre :as log]
            [tick.core :as t])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn get-request-topic [id]
  (str id "/request"))
(defn get-response-topic [id]
  (str id "/response"))

(defn- get-id []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(def handle-message nil)
(defmulti handle-message :message/type)

(defmethod handle-message :default [msg]
  (log/debug ::handle-message :default msg))

(defprotocol IMqttClient
  (connected? [this])
  (publish [this topic message] [this topic message qos])
  (request [this id message success-fn error-fn timeout] [this id message success-fn error-fn timeout qos])
  (subscribe [this topics] [this topic qos])
  (unsubscribe [this topic-or-topics]))

(extend-protocol IMqttClient
  nil
  (connected? [this]
    (throw (ex-info "Tried MQTT connected? with nil" {})))
  (publish [this topic message]
    (throw (ex-info "Tried MQTT send with nil" {:topic topic
                                                :message message})))
  (publish [this topic message qos]
    (throw (ex-info "Tried MQTT send with nil" {:topic topic
                                                :qos qos
                                                :message message})))
  (request [this id message success-fn error-fn timeout]
    (throw (ex-info "Tried MQTT request with nil" {:id id
                                                   :message message
                                                   :success-fn success-fn
                                                   :error-fn error-fn
                                                   :timeout timeout})))
  (request [this id message success-fn error-fn timeout qos]
    (throw (ex-info "Tried MQTT request with nil" {:id id
                                                   :message message
                                                   :success-fn success-fn
                                                   :error-fn error-fn
                                                   :timeout timeout
                                                   :qos qos})))
  (subscribe [this topics]
    (throw (ex-info "Tried MQTT subscribe with nil" {:topics topics})))
  (subscribe [this topic qos]
    (throw (ex-info "Tried MQTT subscribe with nil" {:topic topic
                                                     :qos qos})))
  (unsubscribe [this topic-or-topics]
    (throw (ex-info "Tried MQTT unsubscribe with nil" {:topic-or-topics topic-or-topics}))))


(defn get-default-options [mqtt-client]
  {:on-connect-complete
   (fn [& args]
     (log/debug ::on-connect-complete "Connection completed")
     (let [{:keys [topics on-message]} mqtt-client]
       (when-not (empty? @topics)
         (do (subscribe mqtt-client @topics)
             (log/debug "Resubscribed to topics" @topics)))))
   :on-connection-lost (fn [& args]
                         (log/debug ::on-connection-lost {:args args}))
   :on-delivery-complete (fn [& args]
                           (log/debug ::on-delivery-complete {:args args}))
   :on-unhandled-message (fn [& args]
                           (log/debug ::on-unhandled-message {:args args}))})



;; transit reader/writer from/to string, since
;; mosquitto does not know anything about transit
(defn- ->transit [v]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json {:handlers write-handlers}) v)
    (.toString out "utf-8")))

(defn- <-transit [b]
  (try
    (transit/read (transit/reader (ByteArrayInputStream. b) :json))
    (catch Exception e
      (do (log/warn "Message not in transit format")
          (apply str (map char b))))))


(defn- on-message
  "Handle incoming message from the MQTT broker."
  [mqtt-client]
  (fn
    [^String topic _ ^bytes payload]
    (let [injections (-> mqtt-client
                         (select-keys (:injection-ks mqtt-client))
                         (assoc :mqtt-client mqtt-client))]
      (handle-message (merge {:message/topic topic}
                             (<-transit payload)
                             injections)))))


(defn- get-uri-string [{:keys [scheme host port]}]
  (str scheme "://" host ":" port))

(defn- get-client-options [{:keys [config] :as mqtt-client}]
  (let [{:keys [client-id options connect-options]} config]
    (merge {:client-id (or client-id (mh/generate-id))}
           options
           {:opts (or connect-options {})}
           (get-default-options mqtt-client))))

(defn- connect* [{:keys [config] :as mqtt-client}]
  (log/info (str "Connecting to broker" (select-keys config [:schema :host :port])))
  (let [client (mh/connect (get-uri-string config)
                           (get-client-options mqtt-client))]
    (reset! (:client mqtt-client) client)))

(defn disconnect* [{:keys [client config] :as _mqtt-client}]
  (when client
    (mh/disconnect @client (:disconnect/timeout config))))

(defn- connected?* [{:keys [client] :as _mqtt-client}]
  (mh/connected? @client))

(defn- publish*
  ([mqtt-client topic message]
   (publish mqtt-client topic message (get-in mqtt-client [:config :default-qos] 2)))
  ([{:keys [client message-counter] :as _mqtt-client} topic message qos]
   (mh/publish @client
               topic
               (->transit (assoc message :message/id (swap! message-counter inc)))
               qos)))

(defmethod handle-message :message/request [{:keys [mqtt-client
                                                    message/id
                                                    message.response/topic
                                                    message.type/real] :as message}]
  (log/debug :message/request {:id id
                               :response/topic topic
                               :real real})
  (let [?reply-fn (fn [message]
                    (log/debug :handle-message.message/request :?reply-fn message)
                    (let [message* (assoc message
                                          :message.response/to-id id
                                          :message/type :message/response)]
                      (publish mqtt-client topic message*)))
        updated-message (-> message
                            (dissoc :message/type)
                            (assoc :message/type real
                                   :?reply-fn ?reply-fn))]
    (handle-message updated-message)))

(defmethod handle-message :message/response [{:keys [mqtt-client
                                                     message.response/to-id]
                                              :as message}]
  (log/debug :handle-message :message/response)
  (let [saved-requests (:saved-requests mqtt-client)]
    (log/debug :handle-message.message/response {:saved-requests @saved-requests})
    (when-let [saved-request (get @saved-requests to-id)]
      (log/debug :handle.message/saved-request saved-request)
      (let [{:keys [success-fn]} saved-request]
        (log/debug :handle.message/success-fn success-fn)
        (success-fn message))
      (swap! saved-requests dissoc to-id))))



(defn- request* [{:keys [client message-counter saved-requests] :as mqtt-client}
                 id message success-fn error-fn timeout qos]
  (let [msg-id (swap! message-counter inc)
        msg-type (:message/type message)
        message* (assoc message
                        :message/id msg-id
                        :message.response/topic (get-response-topic (:id mqtt-client))
                        :message/type :message/request
                        :message.type/real msg-type)]
    ;; TODO: Save request for timeout
    (swap! saved-requests assoc msg-id {:success-fn success-fn
                                        :error-fn error-fn
                                        :t (t/now)})
    (log/debug {:topic/request (get-request-topic id)
                :topic/response (get-response-topic (:id mqtt-client))
                :id (:id mqtt-client)
                :message message*})
    (mh/publish @client
                (get-request-topic id)
                (->transit message*)
                qos)))

(defn- subscribe*
  ([{:keys [client on-message] :as mqtt-client} topics]
   (log/info "Subscribing to" topics)
   (mh/subscribe @client topics (on-message mqtt-client)))
  ([{:keys [client on-message] :as mqtt-client} topic qos]
   (let [topics {topic qos}]
     (log/info "Subscribing to" topics)
     (mh/subscribe @client topics (on-message mqtt-client)))))

(defn- unsubscribe* [{:keys [client] :as _mqtt-client} topics]
  (log/info "Unsubscribing from topics" topics)
  (mh/unsubscribe @client topics))


(defrecord MqttClient [config topics client id
                       injection-ks
                       on-message
                       on-connect-complete
                       on-connection-lost
                       on-delivery-complete
                       on-unhandled-message]

  component/Lifecycle
  (start [this]
    (if client
      this
      (do (log/info "Starting MQTT client")
          (log/info (str "Connecting to broker" (select-keys config [:schema :host :port])))
          (let [id (get config :id (get-id))
                new-this (assoc this
                                :id id
                                :saved-requests (atom {})
                                ;; setup the topics now. when the client has connected successfully,
                                ;; they will be subscribed to
                                :topics (atom {(str id "/request") (get config :default-qos 2)
                                               (str id "/response") (get config :default-qos 2)})
                                :client (atom nil)
                                :message-counter (atom 0))]
            (connect* new-this)
            new-this))))
  (stop [this]
    (if (nil? client)
      this
      (do (log/info "Stopping MQTT client")
          (disconnect* this)
          (assoc this
                 :id nil
                 :saved-requests nil
                 :client nil
                 :topics nil
                 :message-counter nil))))
  IMqttClient
  (connected? [this]
    (connected?* this))
  (publish [this topic message]
    (publish* this topic message))
  (publish [this topic message qos]
    (publish* this topic message qos))
  (request [this id message success-fn error-fn timeout]
    (request* this id message success-fn error-fn timeout (get config :default-qos 2)))
  (request [this id message success-fn error-fn timeout qos]
    (request* this id message success-fn error-fn timeout qos))
  (subscribe [this topics*]
    (do (swap! topics merge topics*)
        (subscribe* this topics*)))
  (subscribe [this topic qos]
    (do (swap! topics assoc topic qos)
        (subscribe* this topic qos)))
  (unsubscribe [this topic-or-topics]
    (let [topics* (if (map? topic-or-topics)
                    topic-or-topics
                    {topic-or-topics (get @topics topic-or-topics)})]
      (doseq [k topics*]
        (swap! topics dissoc k))
      (unsubscribe* client topics*))))


(defn mqtt-client [settings]
  (map->MqttClient (merge
                    {:on-message on-message}
                    (assoc-in settings [:config :disconnect/timeout] 10))))
