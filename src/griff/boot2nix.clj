(ns griff.boot2nix
  {:boot/export-tasks true}
  (:require [boot.pod :as pod]
            [boot.core :as core]
            [clojure.java.io :as io]))

(def ^:private deps
  [['boot/core "2.6.0"]
   ['boot/aether "2.6.0"]
   ['org.clojure/data.json "0.2.6"]])

(core/deftask boot2nix
  "Generate project-info.json"
  [l local-repo VAL file "Local repo to scan"
   d default-repo VAL str "Default repo url to use for local artifacts"
   o output VAL str "File to output to. Defaults to project-info.json"]
  (let [p (-> (core/get-env)
              (update-in [:dependencies] into deps)
              pod/make-pod
              future)]
    (fn [handler]
      (fn [fileset]
        ;; TODO: Show selected or all pods
        (let [pod-env (core/get-env)
              project (:task-options (meta #'boot.task.built-in/pom))
              out (or output "project-info.json")]
          (pod/with-call-in @p
            (griff.boot2nix.impl/write-project-info
              ~pod-env
              ~project
              {:local-repo ~(.getAbsolutePath local-repo)
               :default-repo ~default-repo
               :output ~out})))
        (handler fileset)))))
