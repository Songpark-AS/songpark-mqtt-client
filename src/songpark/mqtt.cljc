(ns songpark.mqtt
  (:require [clojure.core.async :as async]
            #?(:cljs ["paho-mqtt" :as Paho])
            [cognitect.transit :as transit]
            [com.stuartsierra.component :as component]
            #?(:cljs [goog.object :as gobj])
            ;;[songpark.mqtt.client :as mh]
            #?(:clj  [clojurewerkz.machine-head.client :as mh])
            #?(:clj  [songpark.mqtt.transit :refer [write-handlers]]
               :cljs [songpark.mqtt.transit :refer [writer reader]])
            [songpark.mqtt.util :refer [broadcast-topic]]
            [taoensso.timbre :as log]
            [tick.core :as t])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

(defn get-request-topic [id]
  (str id "/request"))
(defn get-response-topic [id]
  (str id "/response"))

(defn- get-id []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defmulti handle-message :message/type)

(defmethod handle-message :default [msg]
  (log/warn ::handle-message :default msg))

(defprotocol IMqttClient
  (connected? [client] "Check if we are connected")
  (publish [client topic-or-id message] [client topic-or-id message qos] "Publish to the topic")
  (broadcast [client message] [client message qos] "Broadcast to anything that wants to know about client id broadcast topic")
  (request [client id message success-fn error-fn timeout] [client id message success-fn error-fn timeout qos] "Send a request to an id (will be converted to a topic) and get a response in the success-fn provided. If timed out, the error fn is run instead.")
  (subscribe [client topics] [client topic qos] "Subscribe to topic")
  (unsubscribe [client topic-or-topics] "Unsubscribe from topic")
  (clean-message [client message] "Clean message from any injections like mqtt-client or injection-ks")
  (add-injection [client k data])
  (remove-injection [client k]))

(extend-protocol IMqttClient
  nil
  (connected? [this]
    (throw (ex-info "Tried MQTT connected? with nil" {})))
  (publish
    ([this topic-or-id message]
     (throw (ex-info "Tried MQTT publish with nil" {:topic-or-id topic-or-id
                                                    :message message})))

    ([this topic-or-id message qos]
     (throw (ex-info "Tried MQTT publish with nil" {:topic-or-id topic-or-id
                                                    :qos qos
                                                    :message message}))))
  (broadcast
    ([this message]
     (throw (ex-info "Tried MQTT broadcast with nil" {:message message})))

    ([this message qos]
     (throw (ex-info "Tried MQTT broadcast with nil" {:qos qos
                                                      :message message}))))
  (request
    ([this id message success-fn error-fn timeout]
     (throw (ex-info "Tried MQTT request with nil" {:id id
                                                    :message message
                                                    :success-fn success-fn
                                                    :error-fn error-fn
                                                    :timeout timeout})))
    ([this id message success-fn error-fn timeout qos]
     (throw (ex-info "Tried MQTT request with nil" {:id id
                                                    :message message
                                                    :success-fn success-fn
                                                    :error-fn error-fn
                                                    :timeout timeout
                                                    :qos qos}))))
  (subscribe
    ([this topics]
     (throw (ex-info "Tried MQTT subscribe with nil" {:topics topics})))
    ([this topic qos]
     (throw (ex-info "Tried MQTT subscribe with nil" {:topic topic
                                                      :qos qos}))))
  (unsubscribe [this topic-or-topics]
    (throw (ex-info "Tried MQTT unsubscribe with nil" {:topic-or-topics topic-or-topics})))

  (clean-message [this message]
    (throw (ex-info "Tried MQTT clean-message with nil" {:message message})))

  (add-injection [this k data]
    (throw (ex-info "Tried to add injection with nil" {:k k
                                                       :data data})))

  (remove-injection [this k]
    (throw (ex-info "Tried to remove injection with nil" {:k k}))))


(defn get-default-options [mqtt-client]
  (merge #?(:clj  {:on-connect-complete
                   (fn [& args]
                     (log/info ::on-connect-complete "Connection completed")
                     (let [{:keys [topics on-message]} mqtt-client]
                       (when-not (empty? @topics)
                         (do (subscribe mqtt-client @topics)
                             (log/debug "(re-)subscribed to topics" @topics)))))
                   :on-connection-lost (fn [& args]
                                         (log/info ::on-connection-lost {:args args}))
                   :on-delivery-complete (fn [& args]
                                           (log/info ::on-delivery-complete {:args args}))
                   :on-unhandled-message (fn [& args]
                                           (log/info ::on-unhandled-message {:args args}))}
            :cljs {:on-success (fn [resp]
                                 (log/info ::onSuccess "Connection completed" {:resp resp})
                                 (let [{:keys [topics on-message]} mqtt-client]
                                   (when-not (empty? @topics)
                                     (do (subscribe mqtt-client @topics)
                                         (log/info "(re-)subscribed to topics" @topics)))))
                   :on-failure (fn [resp]
                                 (log/info ::onFailure "Connection failed" {:resp resp}))
                   :on-connection-lost (fn [resp]
                                         (log/info ::on-connection-lost
                                                    (merge {:resp resp
                                                            :error-code (.-errorCode resp)}
                                                           (if-not (zero? (.-errorCode resp))
                                                             {:error-message (.-errorMessage resp)}))))})
         (get-in mqtt-client [:config :default-options])))



;; transit reader/writer from/to string, since
;; mosquitto does not know anything about transit
(defn- ->transit [v]
  #?(:clj  (let [out (ByteArrayOutputStream. 4096)]
             (transit/write (transit/writer out :json {:handlers write-handlers}) v)
             (.toString out "utf-8"))
     :cljs (transit/write writer v)))

(defn- <-transit [v]
  #?(:clj  (try
             (transit/read (transit/reader (ByteArrayInputStream. v) :json))
             (catch Exception e
               (do (log/warn "Message not in transit format")
                   (apply str (map char v)))))
     :cljs (transit/read reader v)))


(defn- on-message
  "Handle incoming message from the MQTT broker."
  [mqtt-client]
  #?(:clj  (fn [^String topic _ ^bytes payload]
             (let [injections (-> @(:injections mqtt-client)
                                  (assoc :mqtt-client mqtt-client))]
               (handle-message (merge {:message/topic topic}
                                      (<-transit payload)
                                      injections))))
     :cljs (fn [message]
             (let [injections (-> @(:injections mqtt-client)
                                  (assoc :mqtt-client mqtt-client))
                   ;; advanced compilation did not like this being a js interop
                   ;; manually grabbing the payload via goog.object/get to fix it
                   payload-string (gobj/get message "payloadString")
                   payload (<-transit payload-string)
                   topic ^String (.-destinationName message)]
               ;; uncomment for debugging. otherwise a bit too spammy
               #_(log/debug ::on-message {:payload-string payload-string
                                        :payload payload
                                        :topic topic})
               (handle-message (merge {:message/topic topic}
                                      payload
                                      injections))))))


(defn- get-uri-string [{:keys [scheme host port]}]
  (str scheme "://" host ":" port))

(defn- get-client-options [{:keys [config] :as mqtt-client}]
  #?(:clj  (let [{:keys [client-id options connect-options]} config]
             (merge {:client-id (or client-id (str (get-id)))}
                    options
                    {:opts (or connect-options {})}
                    (get-default-options mqtt-client)))
     :cljs (let [{:keys [on-connection-lost
                         on-success
                         on-failure]} (get-default-options mqtt-client)]
             {:connection-options (clj->js (merge (select-keys config [:reconnect
                                                                       :useSSL
                                                                       :userName
                                                                       :password
                                                                       :keepAliveInterval
                                                                       :cleanSession
                                                                       :willMessage
                                                                       :invocationContext
                                                                       :timeout
                                                                       :hosts
                                                                       :ports
                                                                       :mqttVersion
                                                                       :mqttVersionExplicit
                                                                       :uris])
                                                  {:onSuccess on-success
                                                   :onFailure on-failure}))
              :on-connection-lost on-connection-lost})))

(defn- connect* [{:keys [config] :as mqtt-client}]
  (log/info (str "Connecting to broker" (select-keys config [:schema :host :port])))
  (let [client #?(:clj  (mh/connect (get-uri-string config)
                                    (get-client-options mqtt-client))
                  :cljs (let [client* ^Paho/Client (Paho/Client. (:host config)
                                                                 (:port config)
                                                                 (or (:client-id config)
                                                                     (str (get-id))))
                              {:keys [connection-options
                                      on-connection-lost]} (get-client-options mqtt-client)]
                          (.connect client* connection-options)
                          (set! (.-onConnectionLost client*) on-connection-lost)
                          client*))]
    (reset! (:client mqtt-client) client)))

(defn disconnect* [{:keys [client config] :as _mqtt-client}]
  (when client
    #?(:clj  (mh/disconnect @client (:disconnect/timeout config))
       :cljs (.disconnect @client))))

(defn- connected?* [{:keys [client] :as _mqtt-client}]
  #?(:clj  (mh/connected? @client)
     :cljs (.isConnected @client)))

(defn- publish*
  ([mqtt-client topic-or-id message]
   (publish* mqtt-client topic-or-id message (get-in mqtt-client [:config :default-qos] 2)))
  ([{:keys [client message-counter] :as _mqtt-client} topic-or-id message qos]
   (let [topic (if (uuid? topic-or-id)
                 (str topic-or-id "/request")
                 topic-or-id)]
     #?(:clj  (mh/publish @client
                          topic
                          (->transit (assoc message :message/id (swap! message-counter inc)))
                          qos)
        :cljs (let [counter (swap! message-counter inc)
                    paho-message (-> message
                                     (assoc :message/id counter)
                                     (->transit)
                                     (Paho/Message.))]
                ;; for debugging purposes
                #_(log/debug "DEBUG" {:counter counter
                                      :message message
                                      :applied (-> message
                                                   (assoc :message/id counter))
                                      :transit (-> message
                                                   (assoc :message/id counter)
                                                   (->transit))})
                (set! (.-destinationName paho-message) topic)
                (set! (.-qos paho-message) qos)
                (.send @client paho-message))))))

(defn- broadcast* [{:keys [id] :as mqtt-client} message qos]
  (publish mqtt-client (broadcast-topic id) message qos))

(defmethod handle-message :message/request [{:keys [mqtt-client
                                                    message/id
                                                    message.response/topic
                                                    message.type/real] :as message}]
  #_(log/debug :message/request {:id id
                               :response/topic topic
                               :real real})
  (let [?reply-fn (fn [message]
                    #_(log/debug :handle-message.message/request :?reply-fn message)
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
  #_(log/debug :handle-message :message/response)
  (let [saved-requests (:saved-requests mqtt-client)]
    #_(log/debug :handle-message.message/response {:saved-requests @saved-requests})
    (when-let [saved-request (get @saved-requests to-id)]
      #_(log/debug :handle.message/saved-request saved-request)
      (let [{:keys [success-fn]} saved-request]
        #_(log/debug :handle.message/success-fn success-fn)
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
    ;; Save request for timeout
    (swap! saved-requests assoc msg-id {:success-fn success-fn
                                        :error-fn error-fn
                                        :timestamp (t/now)
                                        :timeout timeout})
    #_(log/debug {:topic/request (get-request-topic id)
                :topic/response (get-response-topic (:id mqtt-client))
                :id (:id mqtt-client)
                :message message*})
    #?(:clj  (mh/publish @client
                         (get-request-topic id)
                         (->transit message*)
                         qos)
       :cljs (let [paho-message (-> message*
                                    (->transit)
                                    (Paho/Message.))]
               (set! (.-destinationName paho-message) (get-request-topic id))
               (set! (.-qos paho-message) qos)
               (.send @client paho-message)))))

(defn- subscribe*
  ([{:keys [client on-message] :as mqtt-client} topics]
   (log/info "Subscribing to" topics)
   #?(:clj  (mh/subscribe @client topics (on-message mqtt-client))
      :cljs (doseq [[topic qos] topics]
              ;; the implementation for js Paho MQTT does not allow
              ;; for a message handler per topic. since the java version
              ;; does, we do this, which is a bit suboptimal, but gathers
              ;; everything in one place. the same goes for the other arity function
              ;; for subscribe*
              (set! (.-onMessageArrived @client) (on-message mqtt-client))
              (.subscribe @client topic #js {:qos qos}))))
  ([{:keys [client on-message] :as mqtt-client} topic qos]
   (let [topics {topic qos}]
     (log/info "Subscribing to" topics)
     #?(:clj  (mh/subscribe @client topics (on-message mqtt-client))
        :cljs (do
                (set! (.-onMessageArrived @client) (on-message mqtt-client))
                (.subscribe @client topic #js {:qos qos}))))))

(defn- unsubscribe* [{:keys [client] :as _mqtt-client} topics]
  (log/info "Unsubscribing from topics" topics)
  #?(:clj  (mh/unsubscribe @client topics)
     :cljs (doseq [topic topics]
             (.unsubscribe @client topic #js {:timeout 10
                                              :onFailure (fn [& args]
                                                           (log/info "Unsuccessfully unsubscribed" {:topics topics
                                                                                                    :args args}))}))))

(defn- clean-message* [{:keys [injections] :as _mqtt-client} message]
  (apply dissoc message (conj (keys @injections) :mqtt-client)))

(defn check-timeouts
  "Saved requests from the request* function. max-time-in-ms is the maximum time allowed before a request is considered timed out, regardless of the timeout."
  [saved-requests max-time-in-ms]
  (let [now (t/now)]
    #_(log/debug :saved-requests @saved-requests)
    (doseq [[id {:keys [error-fn timestamp timeout]}] @saved-requests]
      #_(log/debug {:id id
                  :now now
                  :timestamp timestamp
                  :max-time-in-ms max-time-in-ms
                  :future (t/>> timestamp (t/new-duration timeout :millis))
                  :true? (t/> now (t/>> timestamp (t/new-duration timeout :millis)))})
      ;; have we timed out? then run error-fn
      (when (or (t/> now (t/>> timestamp (t/new-duration timeout :millis)))
                (t/> now (t/>> timestamp (t/new-duration max-time-in-ms :millis))))
        #_(log/debug :check-timeouts "Running cleanup of requests" {:error-fn error-fn
                                                                  :now now
                                                                  :id id
                                                                  :timeout timeout
                                                                  :max-time-in-ms max-time-in-ms})
        (if error-fn
          (error-fn {:message/id id
                     :reason :timeout})
          (log/warn "Missing error function"))
        (swap! saved-requests dissoc id)))))

(defn- handle-timeout [saved-requests timeout-in-ms max-time-in-ms]
  (let [c (async/chan (async/sliding-buffer 10))]
    (async/go-loop [tc (async/timeout timeout-in-ms)]
      (let [[v ch] (async/alts! [c tc])]
        ;; uncomment for debugging, otherwise leave alone.
        ;; super spammy
        #_(log/debug {:v v
                    :ch ch})
        (cond (and (identical? ch c)
                   (= v :close!))
              (do (log/debug "Closing handle-timeout channel")
                  (async/close! c))

              :else
              (do (try
                    (check-timeouts saved-requests max-time-in-ms)
                    (catch #?@(:clj  [Exception e]
                               :cljs [js/Error e])
                        (log/warn ::handle-timeout {:exception e
                                                    :data (ex-data e)
                                                    :message (ex-message e)})))
                  (recur (async/timeout timeout-in-ms))))))
    ;; run once before returning. otherwise the stuff in the go-loop won't
    ;; run until the timeout has hit the first time
    (check-timeouts saved-requests max-time-in-ms)
    c))

(comment
  (def c-tmp (atom (handle-timeout (atom {1 {:error-fn (fn [data]
                                                         (println data))
                                             :timestamp (t/now)
                                             :timeout 500}}) 1000 2000)))
  (async/put! @c-tmp :close!)
  )


(defrecord MqttClient [config topics client id c-timeout
                       injections
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
          (let [id (get config :id (get-id))
                saved-requests* (atom {})
                new-this (assoc this
                                :c-timeout (handle-timeout saved-requests*
                                                           (get config :request/default-timeout 100)
                                                           (get config :request/max-time 5000))
                                :id id
                                :saved-requests saved-requests*
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
          (async/put! c-timeout :close!)
          (assoc this
                 :c-timeout nil
                 :id nil
                 :saved-requests nil
                 :client nil
                 :topics nil
                 :message-counter nil))))
  IMqttClient
  (connected? [this]
    (connected?* this))
  (publish [this topic-or-id message]
    (publish* this topic-or-id message))
  (publish [this topic-or-id message qos]
    (publish* this topic-or-id message qos))
  (broadcast [this message]
    (broadcast* this message (get config :default-qos 2)))
  (broadcast [this message qos]
    (broadcast* this message qos))
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
    (let [topics* (if (sequential? topic-or-topics)
                    topic-or-topics
                    [topic-or-topics])]
      (doseq [k topics*]
        (swap! topics dissoc k))
      (unsubscribe* this topics*)))
  (clean-message [this message]
    (clean-message* this message))
  (add-injection [this k data]
    (swap! injections assoc k data))
  (remove-injection [this k]
    (swap! injections dissoc k)))

(defmethod clojure.core/print-method MqttClient [data ^java.io.Writer writer]
  (.write writer "#<MQTT Client>"))


(defn mqtt-client [settings]
  (map->MqttClient (merge
                    {:on-message on-message
                     :injections (atom {})}
                    (assoc-in settings [:config :disconnect/timeout] 10))))
