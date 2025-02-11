(ns songpark.mqtt-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [songpark.mqtt.util :refer [broadcast-topic]]
            [taoensso.timbre :as log]))


(def catch (atom nil))
(def reply (atom nil))
(def reply-message {:replied? true})

(defmethod mqtt/handle-message :check-injection [msg]
  (reset! catch (= (:injected? msg) true?)))

(defmethod mqtt/handle-message :foo [msg]
  (reset! catch msg))
(defmethod mqtt/handle-message :reply [{:keys [?reply-fn] :as msg}]
  (if ?reply-fn
    (?reply-fn reply-message)
    (log/error "NO ?reply-fn FOUND!")))
(defmethod mqtt/handle-message :reply-and-sleep [{:keys [?reply-fn sleep] :as msg}]
  (log/info :reply-and-sleep (str "Sleeping for " sleep " ms"))
  (Thread/sleep sleep)
  (log/info :reply-and-sleep "Now replying after the sleep")
  (if ?reply-fn
    (?reply-fn reply-message)
    (log/error "NO ?reply-fn FOUND!")))


(defn get-config []
  {:config {:host "127.0.0.1"
            :scheme "tcp"
            :port 1883
            :connect-options {:auto-reconnect true}}})

(defn start [config]
  (component/start (mqtt/mqtt-client config)))

(defn stop [mqtt-client]
  (component/stop mqtt-client))

(defn init-client []
  (start (get-config)))


(deftest mqtt-client
  (let [client (atom nil)]
    (reset! client (init-client))
    (testing "start"
      (is (not (nil? @(-> @client :client)))))
    (testing "connected?"
      (is (true? (mqtt/connected? @client))))
    (testing "publish"
      (is (nil? (mqtt/publish @client "testclj" {:foo true :client :clj}))))
    (testing "subscribe singular"
      (mqtt/subscribe @client "testclj" 0)
      (is (zero? (get @(:topics @client) "testclj"))))
    (testing "subscribe plural"
      (let [new-topics {"testclj1" 2
                        "testclj2" 2
                        "testclj/foo" 1}]
        (mqtt/subscribe @client new-topics)
        (is (= (select-keys @(:topics @client) (keys new-topics)) new-topics))))
    (testing "catch"
      (let [msg {:message/type :foo :foo true :client :clj}]
        (mqtt/publish @client "testclj" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (= (select-keys @catch (keys msg)) msg))))
    (testing "message counter works"
      (let [msg {:message/type :foo :foo true :client :clj}]
        (mqtt/publish @client "testclj" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (number? (:message/id @catch)))))
    (testing "request/response happy path"
      (let [msg {:message/type :reply :bar :baz :client :clj}
            reply-catch (atom nil)
            success (fn [returned-message]
                      (reset! reply-catch (select-keys returned-message (keys reply-message))))]
        (mqtt/request @client (:id @client) msg success nil 500)
        (Thread/sleep 200)
        (is (= @reply-catch reply-message))))
    (testing "request/response timeout"
      (let [msg {:message/type :reply-and-sleep :sleep 1000 :client :clj}
            reply-catch (atom nil)
            success (fn [returned-message]
                      (reset! reply-catch (select-keys returned-message (keys reply-message))))
            error (fn [returned-message]
                      (reset! reply-catch {:timeout? true}))]
        (mqtt/request @client (:id @client) msg success error 200)
        (Thread/sleep 500)
        (is (= @reply-catch {:timeout? true}))))
    (testing "unsubscribe singular"
      (let [topics (-> client
                       deref
                       :topics
                       deref
                       (dissoc "testclj1"))]
        (mqtt/unsubscribe @client "testclj1")
        (is (= @(:topics @client) topics))))
    (testing "unsubscribe plural"
      (let [topics (-> client
                       deref
                       :topics
                       deref
                       (dissoc "testclj2")
                       (dissoc "testclj/foo"))]
        (mqtt/unsubscribe @client ["testclj2" "testclj/foo"])
        (is (= @(:topics @client) topics))))
    (testing "clean message"
      (let [msg {:message/type :reply :clean? true :client :clj}
            reply-catch (atom nil)
            success (fn [returned-message]
                      (log/debug returned-message)
                      (reset! reply-catch returned-message))
            error (fn [error]
                    (reset! reply-catch error))]
        (mqtt/request @client (:id @client) msg success error 900)
        (Thread/sleep 1000)
        (is (= (-> (mqtt/clean-message @client @reply-catch)
                   (dissoc :message/type :message/id :message/topic :message.response/to-id))
               reply-message))))
    (testing "broadcast"
      (let [msg {:message/type :foo :broadcast true :client :clj}]
        (mqtt/subscribe @client (broadcast-topic (:id @client)) 2)
        (mqtt/broadcast @client msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (= (select-keys @catch (keys msg)) msg))))

    (testing "add injection"
      (let [msg {:message/type :check-injection :client :clj}]
        ;; add the function true? instead of true (which is a value)
        (mqtt/add-injection @client :injected? true?)
        (mqtt/publish @client "testclj" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (true? @catch))))

    (testing "remove injection"
      (let [msg {:message/type :check-injection :client :clj}]
        ;; add the function true? instead of true (which is a value)
        (mqtt/remove-injection @client :injected?)
        (mqtt/publish @client "testclj" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (false? @catch))))
    
    (testing "stop"
      (is (nil? (-> (stop @client) :client))))))
