(ns rendezvous.core
  (:gen-class)
  (:use clojure.tools.cli
        clojure.java.io
        camel-snake-kebab
        [lamina core executor]
        [aleph http formats]
        [cheshire.core :only [generate-string parse-string]]
        compojure.core
        [ring.middleware
         [json-params]
         [reload]])
  (:require [clojure.tools.logging :as log]
            [rendezvous.util :as util]
            [rendezvous.users :as users]
            [compojure.route :as route]
            [hiccup
             [page :refer [html5 include-js]]
             [element :refer [javascript-tag]]]))

(set! *warn-on-reflection* true)

(def aleph-stop (atom nil))

(def default-port 8080)
(def default-host "ws://localhost")

(def highscore-db (agent "highscore.json"))

(def clients (atom {}))
(def waiting (ref '()))
(def games (atom {}))
(def highscores (atom {}))

(defn read-highscores
  []
  (send-off highscore-db
            (fn [db]
              (try
                (let [data (parse-string (slurp db) true)]
                  (log/info data)
                  (reset! highscores data)
                  db)
                (catch Exception e
                  (log/info "couldn't load db file")))
              db))
  nil)

(defn write-highscores
  []
  (send-off highscore-db
            (fn [db]
              (spit db (generate-string @highscores))
              db))
  nil)

(defn- create-uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn- remove-player
  [user]
  (log/info "remove-player")
  (let [{:keys [user-id channel]} user]
    (swap! clients dissoc (:user-id user))
    (dosync (alter waiting #(remove #{user} %))))
  nil)

(defn- stop-game
  [game-id]
  (log/info "stop-game")
  (when-let [game (game-id @games)]
    (doseq [player (:players game)]
      (close (:channel player)))
    (release-named-channel game-id)
    (swap! games dissoc game-id))
  nil)

(defn- tag
  [ch json]
  (-> json
      (parse-string ,, true)
      (assoc ,, :channel ch)))

(defn- untag
  [msg]
  (generate-string (dissoc msg :channel)))

(defn filter-outgress
  [player-channel event]
  (not (= (:channel event) player-channel)))

(defn- wire-player-channels
  [game-id game-channel players]
  (log/info "wire-player-channels" players)
  (doseq [player players]
    (let [player-channel (:channel player)
          p-filter (partial filter-outgress player-channel)]
      (on-closed player-channel (partial stop-game game-id))
      (siphon (map* (partial tag player-channel) player-channel) game-channel)
      (siphon (map* untag (filter* p-filter game-channel)) player-channel))))

(defn- start-game
  [state players max-curse-points]
  (log/info "start-game" players max-curse-points)
  (let [uuid (keyword (create-uuid))
        game-channel (named-channel uuid nil)
        msg {:cmd "start" :players (map :user-id players)
             :max-curse-points max-curse-points}]
    (wire-player-channels uuid game-channel players)
    (swap! games assoc uuid {:players players})
    (enqueue game-channel (util/map-keys ->snake_case msg))))

(defn- maybe-start-game
  [user]
  (log/info "maybe-start-game" user)
  (dosync
   (let [num-players (get-in user [:game-config :num-players])
         filter-fn #(= (get-in % [:game-config :num-players]) num-players)
         similar (filter filter-fn @waiting)]
     (when (>= (count similar) num-players)
       (let [players (take num-players similar)
             _ (log/info "maybe-start-game" players)
             max-curse-points (get-in (last players) [:game-config :max-curse-points])]
         (doseq [player players]
           (alter waiting #(remove #{player} %)))
         (send-off (agent nil) start-game (vec players) max-curse-points))))))

(defn- rendezvous-handler
  [ch handshake]
  (log/info "rendezvous-handler" ch handshake)
  (receive ch
           (fn [msg]
             (let [payload (util/map-keys ->kebab-case (parse-string msg true))
                   num-players (or (:num-players payload) 2)
                   user-id (:user-id payload)
                   game-config {:num-players num-players
                                :max-curse-points (:max-curse-points payload)}
                   user {:user-id user-id :channel ch :game-config game-config}]
               (log/info "rendezvous-handler" num-players)
               (on-closed ch (fn []
                               (log/info (:user-id user) "closed connection")
                               (remove-player user)))
               (swap! clients assoc user-id user)
               (dosync (alter waiting conj user))
               (maybe-start-game user)))))

(defn wrap-bounce-favicon [handler]
  (log/info "bouncing favicon request")
  (fn [req]
    (if (= [:get "/favicon.ico"] [(:request-method req) (:uri req)])
      {:status 404
       :headers {}
       :body ""}
      (handler req))))

(defn- sort-highscores
  [h]
  (if (> (count h) 1)
    (into (sorted-map-by (fn [key1 key2]
                           (compare [(:highscore (get h key2)) key2]
                                    [(:highscore (get h key1)) key1]))) h)
    h))

(defn create-highscore-table
  [h]
  (log/info h)
  [:table
   [:tr
    [:th "Highscore"]
    [:th "Thumbnail"]
    [:th "Name"]
    [:th "Won"]
    (for [[user-id {:keys [highscore won]}] (seq (sort-highscores h))]
      (let [user-data (get users/users (name user-id))]
        [:tr
         [:td highscore]
         [:td [:img {:src (str "pictures/" (:image user-data))}]]
         [:td (:name user-data)]
         [:td (or won 0)]]))]])

(defn- create-highscore-json
  [h]
  {:by_highscore (map (fn [[user-id {:keys [highscore won]}]]
                        (let [user-data (get users/users (name user-id))]
                          {:user-id user-id
                           :highscore highscore
                           :won won
                           :name (:name user-data)
                           :thumbnail (str "pictures/" (:image user-data))}))
                      (seq (sort-highscores h)))})

(defn index-handler
  [request]
  (log/info "index-handler" request)
  (if (= "application/json" ((fnil clojure.string/lower-case "")
                             (get (:headers request) "accept")))
    (generate-string (create-highscore-json @highscores))
    (GET "/" [] (ring.util.response/resource-response "index.html" {:root "public"}))))

(defn highscore-events-handler
  [request-channel request]
  (let [event-channel (channel)
        mapped-channel (map* #(str "data:" (generate-string %) "\n\n") event-channel)]
    (enqueue request-channel {:status 200
                              :headers {"Content-Type" "text/event-stream"}
                              :body mapped-channel})))

(defn- update-highscore
  [old new]
  (if (or (nil? old) (> new old)) new old))

(defn- update-won-matches
  [won-matches won]
  (if won
    ((fnil inc 0) won-matches)
    won-matches))

(defn highscore-handler
  [request]
  (let [body (util/map-keys ->kebab-case (decode-json (:body request)))]
    (swap! highscores (fn [a b]
                        (let [user-id (keyword (:user-id b))
                              highscore (:highscore b)
                              won (:won b)]
                          (-> a
                              (update-in ,, [user-id :highscore] update-highscore highscore)
                              (update-in ,, [user-id :won] update-won-matches won))))
           body))
  (write-highscores)
  {:status 200})

(def handlers
  (routes
   (GET "/" [] index-handler)
   (GET "/ws" [] (wrap-aleph-handler rendezvous-handler))
   (POST "/highscore" [] highscore-handler)
   (GET "/highscore-events" [] (wrap-aleph-handler highscore-events-handler))
   (route/resources "/")
   (route/not-found "Page not found")))

(def app
  (-> handlers
      wrap-bounce-favicon
      wrap-json-params))

(defn start-client
  ([]
     (start-client default-port))
  ([port]
     (start-client default-port default-host))
  ([port host]
     (let [url (str default-host ":" port)
           client @(websocket-client {:url url})]
       (on-closed client #(log/info "closed connection"))
       client)))

(defn start-server
  ([]
     (start-server default-port))
  ([port]
     (let [p (or port (default-port))
           wrapped-handler (wrap-ring-handler app)
           stop-fn (start-http-server wrapped-handler {:port p :websocket true})]
       (reset! aleph-stop stop-fn)
       stop-fn)))

(defn stop-server
  []
  (@aleph-stop))

(defn- at-exit
  [runnable]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable runnable)))

(defn -main
  [& args]
  (log/info "starting rendezvous server...")
  (read-highscores)
  (start-server)
  (at-exit stop-server))