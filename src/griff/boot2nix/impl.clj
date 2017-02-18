(ns griff.boot2nix.impl
  (:require [cemerick.pomegranate.aether :as aether]
            [boot.aether :as boot-aether]
            [boot.pod :as pod]
            [boot.core :as boot-core]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.data.json :as json])
  (:import (org.sonatype.aether.util ChecksumUtils)
           (org.sonatype.aether.resolution ArtifactResult)
           (org.sonatype.aether.repository RemoteRepository)
           (org.sonatype.aether.artifact Artifact)
           (java.io File)))

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

(def default-local-repo
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(defn resolve-artifacts*
  [env]
  (try
    (aether/resolve-artifacts*
      :coordinates       (:dependencies env) #_(keys (boot-aether/resolve-dependencies-memoized* env))
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

(defn artifact-url [{:keys [default-repo]}
                    {:keys [groupId artifactId baseVersion version
                            classifier extension repo]
                     :as artifact}]
  (let [base (cond
               (instance? RemoteRepository repo)
               (.getUrl repo)

               default-repo default-repo

               :else
               (throw (ex-info (str "Missing url for artifact ["
                                    groupId "/" artifactId " \"" baseVersion
                                    "\" :extension \"" extension "\" :classifier \"" classifier "\"]")
                               {:artifact artifact})))]
    (str base
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

(defn add-url [env artifact]
  (merge artifact
         {:url (artifact-url env artifact)}))

(defn add-sha1 [{:keys [file] :as artifact}]
  (merge artifact
         {:sha1 (sha1 file)}))

(defn art-spec [env ^ArtifactResult result]
  (let [artifact (.getArtifact result)]
    (->>
      {:file           (.getFile artifact)
       :repo           (.getRepository result)
       :groupId        (.getGroupId artifact)
       :artifactId     (.getArtifactId artifact)
       :classifier     (.getClassifier artifact)
       :extension      (.getExtension artifact)
       :baseVersion    (.getBaseVersion artifact)
       :version        (.getVersion artifact)
       :authenticated false}
      (add-url env)
      add-sha1)))

(defn cleanup [spec]
  (-> spec
      (set/rename-keys {:baseVersion :version})
      (select-keys [:artifactId :groupId :version :sha1 :classifier
                    :extension :authenticated :url])))
(defn dep-info [env ^ArtifactResult result]
  (let [spec (art-spec env result)
        repo (.getRepository result)
        artifact (.getArtifact result)]
    [(cleanup spec)
     #_(->
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
        (mapcat (partial dep-info env)))))

(defn project-info [env {:keys [project version classifier packaging]}]
  {:project      {:groupId    (when project (namespace project))
                  :artifactId (when project (name project))
                  :version    version
                  :classifier (or classifier "")
                  :extension  (or packaging "pom")}
   :dependencies (deps env)})

(defn extension [file]
  (-> file
      .getName
      (string/split #"\.")
      last))

(defn artifact? [file]
  (->> file
       extension
       (contains? #{"jar" "pom"})))

(defn group-id [local-repo ^File file]
  (let [local-repo (io/as-file local-repo)
        group-part (-> file .getParentFile .getParentFile .getParentFile)
        parent (fn self [seq ^File file]
                 (if (= local-repo file)
                   seq
                   (conj (self seq (.getParentFile file))
                         (.getName file))))]
    (string/join "." (parent [] group-part))))

(defn dep-spec [local-repo file]
  (let [name (.getName file)
        ext (extension file)
        base-version (-> file .getParentFile .getName)
        artifact-id (-> file .getParentFile .getParentFile .getName)
        group-id (group-id local-repo file)
        c-begin (+ 2 (count artifact-id) (count base-version))
        c-end  (- (count name) (count ext) 1)
        classifier (if (> c-end c-begin)
                     (subs name c-begin c-end))]
    (when (string/starts-with?
            name
            (str artifact-id "-" base-version))
      (cond-> [(symbol group-id artifact-id) base-version
               :extension ext]
        classifier (conj :classifier classifier)))))

(defn coordinates [local-repo]
  #_(let [local-repo (io/as-file local-repo)])
  (->> local-repo
       io/as-file
       file-seq
       (filter #(.isFile %))
       (filter artifact?)
       (map #(dep-spec local-repo %))
       (filter #(not (nil? %)))))

(defn write-project-info [env pom-opts {:keys [local-repo output default-repo]}]
  (println (format "scanning %s" local-repo))
  (spit (io/file output)
        (json/write-str
          (project-info
            (assoc boot.pod/env
              :default-repo default-repo
              :local-repo local-repo
              :dependencies (coordinates local-repo))
            pom-opts)))
  (println (format "wrote %s" output)))

(comment
  (coordinates "../frontend/repo")
  (write-project-info
    boot.pod/env
    {}
    {:output "project-info.json"
     :local-repo (io/file "repo")})
  (deps
    (assoc boot.pod/env
      :local-repo (io/file "../frontend/repo")
      :dependencies (coordinates "../frontend/repo")))

  (llsd (io/file "repo") (io/file "repo" "test" "boot" "1.2" "boot-1.2-sources.jar"))
  (->> (io/file "repo")
       file-seq
       (filter #(.isFile %))
       (filter artifact?)
       (map #(dep-spec (io/file "repo") %))
       (filter #(not (nil? %)))
       #_(map #(last (clojure.string/split (.getName %) #"\.")))
       #_distinct)

  (filter #(.isFile %)
          (file-seq (io/file "repo")))
  (.equals (io/file "repo"))
  (boot-core/get-sys-env)
  default-local-repo
  @boot-aether/local-repo
  @boot-aether/default-repositories
  (mapcat #(pod/with-eval-in % (:dependencies boot.pod/env))
       (keys boot.pod/pods))
  boot.core/*boot-version*
  (:local-repo) (boot-core/get-env)

  (deps boot.pod/env)
  (write-project-info
    boot.pod/env
    (:task-options (meta #'boot.task.built-in/pom))
    "project-info.json")

  (print-deps-size boot.pod/env nil)
  (print-deps-size boot.pod/env {:flat? true :sort-by-key :size})
  (print-deps-size boot.pod/env {:flat? true :sort-by-key :recursive-size})
  (add-sizes boot.pod/env {['org.codehaus.plexus/plexus-interpolation "1.14" :scope "test"] nil})
  (add-size boot.pod/env '[org.apache.maven/maven-model-builder "3.0.4" :scope "test"] {{:size 1000000} nil}))
