(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'logback.edn-json-encoder)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Cache basis to avoid repeated resolution
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-clj [_]
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir}))

(defn jar [_]
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :basis @basis
          :scm {:tag version}}))

(defn all [_]
  (clean nil)
  (compile-clj nil)
  (jar nil))
