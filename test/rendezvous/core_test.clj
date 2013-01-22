(ns rendezvous.core-test
  (:use clojure.test
        camel-snake-kebab
        [lamina core executor]
        [aleph http formats]
        rendezvous.core
        [cheshire.core :only [generate-string parse-string]])
  (:require [clojure.tools.logging :as log]
            [rendezvous.util :as util]))

(defn- create-user-join-msg
  [user-id num-players]
  (->> {:user-id user-id :num-players num-players}
       (util/map-keys ->snake_case ,,)
       generate-string))

(deftest start-and-initiate-game-test
  (let [stop-fn (start-server)
        client-1 (start-client)
        client-2 (start-client)]
    (enqueue client-1 (create-user-join-msg 123 2))
    (enqueue client-2 (create-user-join-msg 456 2))
    (doseq [client [client-1 client-2]]
      (let [response (parse-string (deref (read-channel client)) true)]
        (is (= (:cmd response) "start"))
        (is (= (count (:players response)) 2))))
    (stop-fn)))

(deftest send-and-receice-message-test
  (let [stop-fn (start-server)
        a (start-client)
        b (start-client)
        msg {:cmd "move"}]
    (enqueue a (create-user-join-msg 123 2))
    (enqueue b (create-user-join-msg 456 2))
    @(read-channel a)
    @(read-channel b)
    (enqueue a (generate-string msg))
    (is (= msg (parse-string (deref (read-channel b)) true)))
    (stop-fn)))

(deftest start-4-player-game
  (let [user-ids [123 456 789 101]
        stop-fn (start-server)
        players (map (fn [user-id]
                       {:user-id user-id :client (start-client user-id)}) user-ids)]
    (doseq [player players]
      (enqueue (:client player) (create-user-join-msg (:user-id player) 4)))
    (doseq [player players]
      (let [response (parse-string (deref (read-channel (:client player))) true)]
        (is (= (:cmd response) "start"))
        (is (= (count (:players response)) (count user-ids)))))
    (stop-fn)))