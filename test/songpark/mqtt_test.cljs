(ns songpark.mqtt-test
  (:require [clojure.core.async :as async :refer [go timeout <!]]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.mqtt.util :refer [broadcast-topic]]
            [taoensso.timbre :as log]
            [cljs.test :include-macros true :refer [deftest
                                                    is
                                                    testing]]))


(def catch (atom nil))
(def reply (atom nil))
(def reply-message {:replied? true})

(defmethod mqtt/handle-message :foo/cljs [msg]
  (reset! catch msg))
(defmethod mqtt/handle-message :reply/cljs [{:keys [?reply-fn] :as msg}]
  (log/debug :mqtt/handle-message :reply/js)
  (if ?reply-fn
    (?reply-fn reply-message)
    (log/error "NO ?reply-fn FOUND!")))

(defmethod mqtt/handle-message :reply-and-sleep/cljs [{:keys [?reply-fn sleep] :as msg}]
  (log/debug :mqtt/handle-message :reply-and-sleep/js)
  (if ?reply-fn
    (js/setTimeout #(do
                      (log/debug "Sending a late reply")
                      (?reply-fn reply-message)) sleep)
    (log/error "NO ?reply-fn FOUND!")))

(defmethod mqtt/handle-message :die/cljs [{:keys [?reply-fn] :as msg}]
  ;; do nothing. we just want to stop the spamming from :default
  )


(defn get-config
  "Get the config for a CLJS MQTT client. It uses paho mqtt under the hood,
  so you need to follow those particular settings. Use camelCase such as in
  the JS world, as it is translated directly to a JS object in the code."
  []
  {:config {:host "127.0.0.1"
            :port 8000
            :reconnect true}})

(defn start [config]
  (component/start (mqtt/mqtt-client config)))

(defn stop [mqtt-client]
  (component/stop mqtt-client))

(defn init-client []
  (start (get-config)))


(deftest mqtt-client
  (go
    (let [client (atom nil)
          sleep-timer 1000]
      (reset! client (init-client))
      (testing "start"
        (is (not (nil? @(-> @client :client)))))

      ;; sleep
      (<! (timeout sleep-timer))
      
      (testing "connected?"
        (is (true? (mqtt/connected? @client))))
      (testing "publish"
        (is (nil? (mqtt/publish @client "testcljs" {:message/type :die/cljs :foo true :client :cljs}))))
      (testing "subscribe singular"
        (mqtt/subscribe @client "testcljs" 0)
        (is (zero? (get @(:topics @client) "testcljs"))))
      (testing "subscribe plural"
        (let [new-topics {"testcljs1" 2
                          "testcljs2" 2
                          "testcljs/foo" 1}]
          (mqtt/subscribe @client new-topics)
          (is (= (select-keys @(:topics @client) (keys new-topics)) new-topics))))
      (testing "catch"
        (let [msg {:message/type :foo/cljs :foo true :client :cljs}]
          (mqtt/publish @client "testcljs" msg)
          ;; sleep for 200ms to catch the message
          (<! (timeout 200))
          (is (= (select-keys @catch (keys msg)) msg))))
      (testing "message counter works"
        (let [msg {:message/type :foo/cljs :foo true :client :cljs}]
          (mqtt/publish @client "testcljs" msg)

          ;; sleep for 200ms to catch the message
          (<! (timeout 200))
          
          (is (number? (:message/id @catch)))))
      (testing "request/response happy path"
        (let [msg {:message/type :reply/cljs :bar :baz}
              reply-catch (atom nil)
              success (fn [returned-message]
                        (log/info "I have returned")
                        (reset! reply-catch (select-keys returned-message (keys reply-message))))]
          (mqtt/request @client (:id @client) msg success nil 500)

          ;; sleep in order to catch the message
          ;; needs to be a staggered approach, as the timeout allows for
          ;; the network traffic to happen, and then let the nodejs single thread
          ;; continue with its work before it gets trapped in a timeout (again),
          ;; allowing it to run any callbacks before continuing with the the code below
          (doseq [_ (range 5)]
            (<! (timeout 200)))
          
          (is (= @reply-catch reply-message))))
      (testing "request/response timeout"
        (let [msg {:message/type :reply-and-sleep/cljs :sleep 2000}
              reply-catch-timeout (atom nil)
              success (fn [returned-message]
                        (reset! reply-catch-timeout (select-keys returned-message (keys reply-message))))
              error (fn [returned-message]
                        (reset! reply-catch-timeout {:timeout? true}))]
          (mqtt/request @client (:id @client) msg success error 500)

          ;; sleep in order to catch the message
          ;; needs to be a staggered approach, as the timeout allows for
          ;; the network traffic to happen, and then let the nodejs single thread
          ;; continue with its work before it gets trapped in a timeout (again),
          ;; allowing it to run any callbacks before continuing with the the code below
          (doseq [_ (range 5)]
            (<! (timeout 200)))
          
          (is (= @reply-catch-timeout {:timeout? true}))))
      (testing "unsubscribe singular"
        (let [topics (-> client
                         deref
                         :topics
                         deref
                         (dissoc "testcljs1"))]
          (mqtt/unsubscribe @client "testcljs1")
          (is (= @(:topics @client) topics))))
      (testing "unsubscribe plural"
        (let [topics (-> client
                         deref
                         :topics
                         deref
                         (dissoc "testcljs2")
                         (dissoc "testcljs/foo"))]
          (mqtt/unsubscribe @client ["testcljs2" "testcljs/foo"])
          (is (= @(:topics @client) topics))))
      (testing "clean message"
        (let [msg {:message/type :reply/cljs :clean? true :client :cljs}
              reply-catch (atom nil)
              success (fn [returned-message]
                        (log/debug returned-message)
                        (reset! reply-catch returned-message))
              error (fn [error]
                      (log/debug "MEH!")
                      (reset! reply-catch error))]
          (mqtt/request @client (:id @client) msg success error 500)
          (doseq [_ (range 5)]
            (<! (timeout 200)))
          (is (= (-> (mqtt/clean-message @client @reply-catch)
                     (dissoc :message/type :message/id :message/topic :message.response/to-id))
                 reply-message))))
      (testing "broadcast"
        (let [msg {:message/type :foo/cljs :broadcast true :client :cljs}]
          (mqtt/subscribe @client (broadcast-topic (:id @client)) 2)
          (mqtt/broadcast @client msg)
          ;; sleep for 200ms to catch the message
          (doseq [_ (range 5)]
            (<! (timeout 200)))
          (is (= (select-keys @catch (keys msg)) msg))))
      (testing "stop"
        ;; sleep for 1000ms to catch the message
        (doseq [_ (range 5)]
          (<! (timeout 200)))
        (is (nil? (-> (stop @client) :client))))
      )))
