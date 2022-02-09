(ns songpark.test-runner
  (:require ;; [doo.runner :refer-macros [doo-tests]]
            [songpark.mqtt-test]
            [taoensso.timbre :as log]))

;; (doo-tests 'songpark.mqtt-test)


(defn start []
  (songpark.mqtt-test/mqtt-client))

(defn stop [done]
  ;; tests can be async. You must call done so that the runner knows you actually finished
  (done))

(defn ^:export init []
  (start))
