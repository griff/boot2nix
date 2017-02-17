(ns griff.boot2nix
  {:boot/export-tasks true}
  (:require [boot.pod :as pod]
            [boot.core :as core]
            [clojure.java.io :as io]))

(def ^:private deps
  [['boot/aether "2.6.0"]
   ['org.clojure/data.json "0.2.6"]])

(core/deftask boot2nix
  "Generate project-info.json"
  []
  (let [p (-> (core/get-env)
              (update-in [:dependencies] into deps)
              pod/make-pod
              future)]
    (fn [handler]
      (fn [fileset]
        ;; TODO: Show selected or all pods
        (let [pod-env (core/get-env)
              project (:task-options (meta #'boot.task.built-in/pom))]
          (pod/with-call-in @p
            (griff.boot2nix.impl/write-project-info
              ~pod-env
              ~project
              "project-info.json")))
        (handler fileset)))))
