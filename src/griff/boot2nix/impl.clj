(ns griff.boot2nix.impl
  (:require [cemerick.pomegranate.aether :as aether]
            [boot.aether :as boot-aether]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.data.json :as json])
  (:import (org.sonatype.aether.util ChecksumUtils)
           (org.sonatype.aether.resolution ArtifactResult)
           (org.sonatype.aether.repository RemoteRepository)
           (org.sonatype.aether.artifact Artifact)))

(defn sha1 [file]
  (let [res (ChecksumUtils/calc file ["SHA-1"])]
    (get res "SHA-1")))

(defn add-size [env dep deps]
  (let [jar (io/file (pod/resolve-dependency-jar env dep))
        size (.length jar)]
    (assoc (pod/coord->map dep)
           :jar jar
           :size size
           :recursive-size (reduce + size (map :size deps)))))

(defn add-sizes [env m]
  (for [[dep deps] m]
    (let [deps (if deps (add-sizes env deps))]
      (assoc (add-size env dep deps) :deps deps))))

(defn deps-size
  [env]
  (-> env
      boot-aether/resolve-dependencies-memoized*
      (->> (aether/dependency-hierarchy (:dependencies env))
           (add-sizes env))))

(defn flatten-deps [x]
  (mapcat (fn [dep]
            (conj (flatten-deps (:deps dep))
                  (dissoc dep :deps)))
          x))

(defn resolve-artifacts*
  [env]
  (try
    (aether/resolve-artifacts*
      :coordinates       (keys (boot-aether/resolve-dependencies-memoized* env))
      :repositories      (->> (or (seq (:repositories env)) @boot-aether/default-repositories)
                              (map (juxt first (fn [[x y]] (if (map? y) y {:url y}))))
                              (map (juxt first (fn [[x y]] (update-in y [:update] #(or % @boot-aether/update?))))))
      :local-repo        (or (:local-repo env) @boot-aether/local-repo nil)
      :offline?          (or @boot-aether/offline? (:offline? env))
      :mirrors           (merge @boot-aether/default-mirrors (:mirrors env))
      :proxy             (or (:proxy env) (boot-aether/get-proxy-settings))
      :transfer-listener boot-aether/transfer-listener
      :repository-session-fn (if (= @boot-aether/update? :always)
                               #(doto (aether/repository-session %)
                                  (.setUpdatePolicy (aether/update-policies :always)))
                               aether/repository-session))
    (catch Exception e
      (let [root-cause (last (take-while identity (iterate (memfn getCause) e)))]
        (if-not (and (not @boot-aether/offline?) (instance? java.net.UnknownHostException root-cause))
          (throw e)
          (do (reset! boot-aether/offline? true)
              (resolve-artifacts* env)))))))

(defn pom-file [^Artifact artifact]
  (io/file
    (.getParentFile (.getFile artifact))
    (str (.getArtifactId artifact)
         "-" (.getVersion artifact)
         ".pom")))

(defn artifact-url [{:keys [groupId artifactId baseVersion version
                            classifier extension repo]
                     :as artifact}]
  (when (instance? RemoteRepository repo)
    (str (.getUrl repo)
         "/" (string/replace groupId
                             #"\."
                             "/")
         "/" artifactId
         "/" baseVersion
         "/" artifactId
         "-" version
         (when-not (= "" classifier)
           (str "-" classifier))
         "." extension)))

(defn add-url [artifact]
  (merge artifact
         {:url (artifact-url artifact)}))

(defn add-sha1 [{:keys [file] :as artifact}]
  (merge artifact
         {:sha1 (sha1 file)}))

(defn art-spec [^ArtifactResult result]
  (let [artifact (.getArtifact result)]
    (->
      {:file           (.getFile artifact)
       :repo           (.getRepository result)
       :groupId        (.getGroupId artifact)
       :artifactId     (.getArtifactId artifact)
       :classifier     (.getClassifier artifact)
       :extension      (.getExtension artifact)
       :baseVersion    (.getBaseVersion artifact)
       :version        (.getVersion artifact)
       :authenticated false}
      add-url
      add-sha1)))

(defn cleanup [spec]
  (-> spec
      (set/rename-keys {:baseVersion :version})
      (select-keys [:artifactId :groupId :version :sha1 :classifier
                    :extension :authenticated :url])))
(defn dep-spec [^ArtifactResult result]
  (let [spec (art-spec result)
        repo (.getRepository result)
        artifact (.getArtifact result)]
    [(cleanup spec)
     (->
       spec
       (merge
         {:file (pom-file artifact)
          :classifier ""
          :extension  "pom"})
       add-url
       add-sha1
       cleanup)]))

(defn deps [env]
  (-> env
      resolve-artifacts*
      (->>
        (mapcat dep-spec))))

(defn project-info [env {:keys [project version classifier packaging]}]
  {:project      {:groupId    (when project (namespace project))
                  :artifactId (when project (name project))
                  :version    version
                  :classifier (or classifier "")
                  :extension  (or packaging "pom")}
   :dependencies (deps env)})

(defn write-project-info [env pom-opts file]
  (spit (io/file file) (json/write-str (project-info env pom-opts)))
  (println (format "wrote %s" file)))

(comment
  @boot-aether/default-repositories
  boot.pod/env
  (write-project-info
    boot.pod/env
    (:task-options (meta #'boot.task.built-in/pom))
    "project-info.json")

  (print-deps-size boot.pod/env nil)
  (print-deps-size boot.pod/env {:flat? true :sort-by-key :size})
  (print-deps-size boot.pod/env {:flat? true :sort-by-key :recursive-size})
  (add-sizes boot.pod/env {['org.codehaus.plexus/plexus-interpolation "1.14" :scope "test"] nil})
  (add-size boot.pod/env '[org.apache.maven/maven-model-builder "3.0.4" :scope "test"] {{:size 1000000} nil}))
