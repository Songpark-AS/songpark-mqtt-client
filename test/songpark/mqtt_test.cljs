(ns songpark.mqtt-test
  (:require [clojure.core.async :as async :refer [go timeout <!]]
            [com.stuartsierra.component :as component]
            [songpark.mqtt :as mqtt]
            [taoensso.timbre :as log]
            [cljs.test :include-macros true :refer [async
                                                    deftest
                                                    is
                                                    testing]]))


(def catch (atom nil))
(def reply (atom nil))
(def reply-message {:replied? true})

(defmethod mqtt/handle-message :foo/cljs [msg]
  (reset! catch msg))
(defmethod mqtt/handle-message :reply/cljs [{:keys [?reply-fn] :as msg}]
  (if ?reply-fn
    (?reply-fn reply-message)
    (log/error "NO ?reply-fn FOUND!")))

(defmethod mqtt/handle-message :die/cljs [{:keys [?reply-fn] :as msg}]
  ;; do nothing. we just want to stop the spamming from :default
  )

(defmethod mqtt/handle-message :reply-and-sleep/cljs [{:keys [?reply-fn sleep] :as msg}]
  (log/info :reply-and-sleep (str "Sleeping for " sleep " ms"))
  ;; (Thread/sleep sleep)
  (log/info :reply-and-sleep "Now replying after the sleep")
  (if ?reply-fn
    (?reply-fn reply-message)
    (log/error "NO ?reply-fn FOUND!")))


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
                        (reset! reply-catch (select-keys returned-message (keys reply-message))))]
          (mqtt/request @client (:id @client) msg success nil 500)

          ;; sleep for 200ms to catch the message
          (<! (timeout 200))
          
          (is (= @reply-catch reply-message))))
      ;; (testing "request/response timeout"
      ;;   (let [msg {:message/type :reply-and-sleep/cljs :sleep 1000}
      ;;         reply-catch (atom nil)
      ;;         success (fn [returned-message]
      ;;                   (reset! reply-catch (select-keys returned-message (keys reply-message))))
      ;;         error (fn [returned-message]
      ;;                   (reset! reply-catch {:timeout? true}))]
      ;;     (mqtt/request @client (:id @client) msg success error 200)
      ;;     (Thread/sleep 500)
      ;;     (is (= @reply-catch {:timeout? true}))))
      (testing "stop"
        ;; sleep for 1000ms to catch the message
        (<! (timeout 1000))
        (is (nil? (-> (stop @client) :client))))
      )))
