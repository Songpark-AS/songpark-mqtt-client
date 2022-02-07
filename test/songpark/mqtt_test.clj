(ns songpark.mqtt-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [taoensso.timbre :as log]))


(def catch (atom nil))
(def reply (atom nil))
(def reply-message {:replied? true})

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
      (is (not (nil? (-> @client :client)))))
    (testing "connected?"
      (mqtt/connected? @client))
    (testing "publish"
      (is (nil? (mqtt/publish @client "test" {:foo true}))))
    (testing "subscribe singular"
      (mqtt/subscribe @client "test" 0)
      (is (zero? (get @(:topics @client) "test"))))
    (testing "subscribe plural"
      (let [new-topics {"test1" 2
                        "test2" 2
                        "test/foo" 1}]
        (mqtt/subscribe @client new-topics)
        (is (= (select-keys @(:topics @client) (keys new-topics)) new-topics))))
    (testing "catch"
      (let [msg {:message/type :foo :foo true}]
        (mqtt/publish @client "test" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (= (select-keys @catch (keys msg)) msg))))
    (testing "message counter works"
      (let [msg {:message/type :foo :foo true}]
        (mqtt/publish @client "test" msg)
        ;; sleep for 200ms to catch the message
        (Thread/sleep 200)
        (is (number? (:message/id @catch)))))
    (testing "request/response happy path"
      (let [msg {:message/type :reply :bar :baz}
            reply-catch (atom nil)
            success (fn [returned-message]
                      (reset! reply-catch (select-keys returned-message (keys reply-message))))]
        (mqtt/request @client (:id @client) msg success nil 500)
        (Thread/sleep 200)
        (is (= @reply-catch reply-message))))
    (testing "request/response timeout"
      (let [msg {:message/type :reply-and-sleep :sleep 1000}
            reply-catch (atom nil)
            success (fn [returned-message]
                      (reset! reply-catch (select-keys returned-message (keys reply-message))))
            error (fn [returned-message]
                      (reset! reply-catch {:timeout? true}))]
        (mqtt/request @client (:id @client) msg success error 200)
        (Thread/sleep 500)
        (is (= @reply-catch {:timeout? true}))))
    (testing "stop"
      (is (nil? (-> (stop @client) :client))))))
