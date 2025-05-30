(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.github.hamann/edn-json-encoder)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Cache basis to avoid repeated resolution
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def pom-data
  {:group-id "com.github.hamann"
   :artifact-id "edn-json-encoder"
   :version version
   :description "A Logback encoder that converts EDN format logs to JSON for better integration with log aggregation systems"
   :url "https://github.com/hamann/logback-edn-json-encoder"
   :licenses [{:name "Eclipse Public License 2.0"
               :url "https://www.eclipse.org/legal/epl-2.0/"}]
   :developers [{:name "Holger Amann"
                 :email "holger.amann@mailbox.org"}]
   :scm {:url "https://github.com/hamann/logback-edn-json-encoder"
         :connection "scm:git:git://github.com/hamann/logback-edn-json-encoder.git"
         :developer-connection "scm:git:ssh://git@github.com/hamann/logback-edn-json-encoder.git"
         :tag version}})

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-clj [_]
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data [[:description (:description pom-data)]
                           [:url (:url pom-data)]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]
                           [:developers
                            [:developer
                             [:name "Holger Amann"]
                             [:email "holger.amann@mailbox.org"]]]
                           [:scm
                            [:url (:url (:scm pom-data))]
                            [:connection (:connection (:scm pom-data))]
                            [:developerConnection (:developer-connection (:scm pom-data))]
                            [:tag (:tag (:scm pom-data))]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [_]
  "Deploy to Maven repository. Requires environment variables for credentials."
  (b/copy-file {:src jar-file
                :target (str "target/" (name lib) "-" version ".jar")})
  (println "JAR ready for deployment. Use 'bb publish' to deploy to Maven."))

(defn all [_]
  (clean nil)
  (compile-clj nil)
  (jar nil))
