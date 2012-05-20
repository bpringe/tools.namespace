;;; find_namespaces.clj: search for ns declarations in dirs, JARs, or CLASSPATH

;; by Stuart Sierra, http://stuartsierra.com/
;; April 19, 2009

;; Copyright (c) Stuart Sierra, 2009. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.


(ns 
  ^{:author "Stuart Sierra",
     :doc "Search for ns declarations in dirs, JARs, or CLASSPATH"} 
  clojure.tools.namespace
  (:require [clojure.java.classpath :as classpath]
            [clojure.set :as set])
  (import (java.io File FileReader BufferedReader PushbackReader
                   InputStreamReader)
          (java.util.jar JarFile)))


;;; Finding namespaces in a directory tree

(defn clojure-source-file?
  "Returns true if file is a normal file with a .clj extension."
  [^File file]
  (and (.isFile file)
       (.endsWith (.getName file) ".clj")))

(defn find-clojure-sources-in-dir
  "Searches recursively under dir for Clojure source files (.clj).
  Returns a sequence of File objects, in breadth-first sort order."
  [^File dir]
  ;; Use sort by absolute path to get breadth-first search.
  (sort-by #(.getAbsolutePath ^File %)
           (filter clojure-source-file? (file-seq dir))))

(defn comment?
  "Returns true if form is a (comment ...)"
  [form]
  (and (list? form) (= 'comment (first form))))

(defn ns-decl?
  "Returns true if form is a (ns ...) declaration."
  [form]
  (and (list? form) (= 'ns (first form))))

(defn read-ns-decl
  "Attempts to read a (ns ...) declaration from rdr, and returns the
  unevaluated form.  Returns nil if read fails or if a ns declaration
  cannot be found.  The ns declaration must be the first Clojure form
  in the file, except for (comment ...)  forms."
  [^PushbackReader rdr]
  (try
   (loop [] (let [form (doto (read rdr) str)]
              (cond
               (ns-decl? form) form
               (comment? form) (recur)
               :else nil)))
       (catch Exception e nil)))

(defn read-file-ns-decl
  "Attempts to read a (ns ...) declaration from file, and returns the
  unevaluated form.  Returns nil if read fails, or if the first form
  is not a ns declaration."
  [^File file]
  (with-open [rdr (PushbackReader. (BufferedReader. (FileReader. file)))]
    (read-ns-decl rdr)))

(defn find-ns-decls-in-dir
  "Searches dir recursively for (ns ...) declarations in Clojure
  source files; returns the unevaluated ns declarations."
  [^File dir]
  (filter identity (map read-file-ns-decl (find-clojure-sources-in-dir dir))))

(defn find-namespaces-in-dir
  "Searches dir recursively for (ns ...) declarations in Clojure
  source files; returns the symbol names of the declared namespaces."
  [^File dir]
  (map second (find-ns-decls-in-dir dir)))


;;; Finding namespaces in JAR files

(defn clojure-sources-in-jar
  "Returns a sequence of filenames ending in .clj found in the JAR file."
  [^JarFile jar-file]
  (filter #(.endsWith ^String % ".clj") (classpath/filenames-in-jar jar-file)))

(defn read-ns-decl-from-jarfile-entry
  "Attempts to read a (ns ...) declaration from the named entry in the
  JAR file, and returns the unevaluated form.  Returns nil if the read
  fails, or if the first form is not a ns declaration."
  [^JarFile jarfile ^String entry-name]
  (with-open [rdr (PushbackReader.
                   (BufferedReader.
                    (InputStreamReader.
                     (.getInputStream jarfile (.getEntry jarfile entry-name)))))]
    (read-ns-decl rdr)))

(defn find-ns-decls-in-jarfile
  "Searches the JAR file for Clojure source files containing (ns ...)
  declarations; returns the unevaluated ns declarations."
  [^JarFile jarfile]
  (filter identity
          (map #(read-ns-decl-from-jarfile-entry jarfile %)
               (clojure-sources-in-jar jarfile))))

(defn find-namespaces-in-jarfile
  "Searches the JAR file for Clojure source files containing (ns ...)
  declarations.  Returns a sequence of the symbol names of the
  declared namespaces."
  [^JarFile jarfile]
  (map second (find-ns-decls-in-jarfile jarfile)))


;;; Finding namespaces anywhere on CLASSPATH

(defn find-ns-decls-on-classpath
  "Searches CLASSPATH (both directories and JAR files) for Clojure
  source files containing (ns ...) declarations.  Returns a sequence
  of the unevaluated ns declaration forms."
  []
  (concat
   (mapcat find-ns-decls-in-dir (classpath/classpath-directories))
   (mapcat find-ns-decls-in-jarfile (classpath/classpath-jarfiles))))

(defn find-namespaces-on-classpath
  "Searches CLASSPATH (both directories and JAR files) for Clojure
  source files containing (ns ...) declarations.  Returns a sequence
  of the symbol names of the declared namespaces."
  []
  (map second (find-ns-decls-on-classpath)))


;;; Parsing dependencies

(defn- deps-from-libspec [prefix form]
  (cond (list? form) (apply set/union (map (fn [f] (deps-from-libspec
						(symbol (str (when prefix (str prefix "."))
							     (first form)))
						f))
				       (rest form)))
	(vector? form) (deps-from-libspec prefix (first form))
	(symbol? form) #{(symbol (str (when prefix (str prefix ".")) form))}
	(keyword? form) #{}
	:else (throw (IllegalArgumentException.
		      (pr-str "Unparsable namespace form:" form)))))

(defn- deps-from-ns-form [form]
  (when (and (list? form)
	     (contains? #{:use :require} (first form)))
    (apply set/union (map #(deps-from-libspec nil %) (rest form)))))

(defn deps-from-ns-decl
  "Given a (quoted) ns declaration, returns a set of symbols naming
  the dependencies of that namespace.  Handles :use and :require
  clauses but not :load."
  [decl]
  (apply set/union (map deps-from-ns-form decl)))
