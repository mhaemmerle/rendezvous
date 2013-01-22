(ns rendezvous.util
  (:use camel-snake-kebab))

;; lifted from https://github.com/qerub/camel-snake-kebab
(defn map-keys [f m]
  (letfn [(mapper [[k v]] [(f k) (if (map? v) (map-keys f v) v)])]
    (into {} (map mapper m))))