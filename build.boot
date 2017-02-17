(def +version+ "1.0.0")

(set-env!
  :resource-paths #{"src"}
  :dependencies   '[[org.clojure/clojure "1.8.0" :scope "provided"]
                    [boot/core "2.6.0" :scope "provided"]
                    [boot/aether "2.6.0" :scope "test"]
                    [org.clojure/data.json "0.2.6" :scope "test"]
                    [adzerk/bootlaces "0.1.13" :scope "test"]])

(require '[adzerk.bootlaces :refer :all])
(bootlaces! +version+)

(task-options!
  pom {:project     'griff/boot2nix
       :version     +version+
       :description ""
       :url         "https://github.com/griff/boot2nix"
       :scm         {:url "https://github.com/gridd/boot2nix"}
       :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask build []
  (comp
    (pom)
    (jar)
    (install)))

(deftask dev []
  (comp
   (watch)
   (repl :server true)
   (build)
   (target)))

(deftask deploy []
  (comp
   (build)
   (push-release)
   #_(push :repo "clojars" :gpg-sign (not (.endsWith +version+ "-SNAPSHOT")))))
