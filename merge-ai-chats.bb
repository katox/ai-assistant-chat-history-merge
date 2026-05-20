#!/usr/bin/env bb

(ns merge-ai-chats
    (:require [clojure.data.xml :as xml]
      [clojure.java.io :as io]
      [clojure.set :as set]
      [clojure.string :as str]
      [babashka.fs :as fs])
    (:import [java.math BigInteger]
      [java.security MessageDigest]))

;; ----------------------------------------------------------------------------
;; Config / patterns
;; ----------------------------------------------------------------------------

(def intellij-version "IntelliJIdea2026.1")

(def default-old-dir
  (str (System/getProperty "user.home")
       "/.config/JetBrains/" intellij-version "/workspace"))

(def default-new-dir
  "./workspace")

(def ai-component-pattern
  #"(?i)(agent|assistant|ai|llm|chat|prompt|conversation)")

(def persistent-chat-component-pattern
  #"(?i)(chat|conversation|prompt|session)")

(def temp-component-pattern
  #"(?i)(temp|temporary)")

(def chat-file-pattern
  #"(?i)(ai-chats|chat-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

;; ----------------------------------------------------------------------------
;; CLI / logging
;; ----------------------------------------------------------------------------

(defn debugf [debug? & xs]
      (when debug?
            (binding [*out* *err*]
                     (apply println xs))))

(defn die! [& xs]
      (binding [*out* *err*]
               (apply println xs))
      (System/exit 1))

(defn usage! []
      (binding [*out* *err*]
               (println "Usage:")
               (println "  ./merge-ai-chats.bb <project-search-string> [old-workspace-dir] [new-workspace-dir] [--debug] [--target-uuid UUID]")
               (println)
               (println "Defaults:")
               (println "  old-workspace-dir:" default-old-dir)
               (println "  new-workspace-dir:" default-new-dir)
               (println)
               (println "Options:")
               (println "  --debug             Print diagnostics")
               (println "  --target-uuid UUID  Select the old/main workspace XML by filename stem")
               (println)
               (println "Output:")
               (println "  ./merged/ as a complete new workspace directory")
               (println "  ./merged/<old-main-uuid>.xml as the merged main workspace XML"))
      (System/exit 2))

(defn parse-main-args [args]
      (when (or (empty? args)
                (#{"-h" "--help"} (first args)))
            (usage!))
      (loop [xs (seq args)
             positional []
             opts {:debug?      false
                   :target-uuid nil}]
            (if (empty? xs)
              (let [[project old-dir new-dir & extra] positional]
                   (when (seq extra)
                         (die! "Too many positional arguments. Use --help for usage."))
                   (when-not project
                             (usage!))
                   {:project     project
                    :old-dir     (or old-dir default-old-dir)
                    :new-dir     (or new-dir default-new-dir)
                    :debug?      (:debug? opts)
                    :target-uuid (:target-uuid opts)})
              (let [x (first xs)]
                   (cond
                     (= x "--debug")
                     (recur (next xs) positional (assoc opts :debug? true))

                     (= x "--target-uuid")
                     (let [v (second xs)]
                          (when-not v
                                    (die! "Missing value after --target-uuid"))
                          (recur (nnext xs) positional (assoc opts :target-uuid v)))

                     :else
                     (recur (next xs) (conj positional x) opts))))))

;; ----------------------------------------------------------------------------
;; Basic file / XML utilities
;; ----------------------------------------------------------------------------

(defn parse-xml [path]
      (xml/parse-str (slurp path)))

(defn write-xml! [doc path]
      (with-open [w (io/writer path)]
                 (xml/emit doc w)))

(defn element? [x]
      (and (map? x) (contains? x :tag)))

(defn child-elements
      ([node]
       (filter element? (:content node)))
      ([node tag-name]
       (filter #(= tag-name (:tag %))
               (child-elements node))))

(defn descendants [node]
      (tree-seq element? child-elements node))

(defn attr [el k]
      (get-in el [:attrs k]))

(defn components [doc]
      (child-elements doc :component))

(defn component-name [component]
      (attr component :name))

(defn component-map [doc]
      (into {}
            (keep (fn [component]
                      (when-let [name (component-name component)]
                                [name component])))
            (components doc)))

(defn sha-256-string [s]
      (let [digest (MessageDigest/getInstance "SHA-256")
            bytes (.getBytes (str s) "UTF-8")]
           (.update digest bytes)
           (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn sha-256-file [^java.io.File file]
      (let [digest (MessageDigest/getInstance "SHA-256")]
           (with-open [in (io/input-stream file)]
                      (let [buf (byte-array 8192)]
                           (loop []
                                 (let [n (.read in buf)]
                                      (when (pos? n)
                                            (.update digest buf 0 n)
                                            (recur))))))
           (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn file-content-relation [^java.io.File a ^java.io.File b]
      (let [a-sha (sha-256-file a)
            b-sha (sha-256-file b)]
           {:same? (= a-sha b-sha)
            :a-sha a-sha
            :b-sha b-sha}))

(defn safe-filename-part [s]
      (-> s
          (str/replace #"[^A-Za-z0-9._-]+" "-")
          (str/replace #"^-+|-+$" "")))

(defn file-uuid [^java.io.File file]
      (str/replace (.getName file) #"\.xml$" ""))

(defn merged-output-file [out-dir project old-file]
      (io/file out-dir (.getName ^java.io.File old-file)))

(defn copy-workspace-dir! [source-dir target-dir]
      (let [source (fs/path source-dir)
            target (fs/path target-dir)]
           (when (fs/exists? target)
                 (fs/delete-tree target))
           (fs/create-dirs target)
           (fs/copy-tree source target {:replace-existing true})
           target))

;; ----------------------------------------------------------------------------
;; Component predicates
;; ----------------------------------------------------------------------------

(defn ai-component? [component]
      (boolean
        (when-let [name (component-name component)]
                  (re-find ai-component-pattern name))))

(defn temp-component? [component]
      (boolean
        (when-let [name (component-name component)]
                  (re-find temp-component-pattern name))))

(defn copy-missing-component?
      "Conservative default: copy persistent-looking chat/session components, but
       skip temp components."
      [component]
      (boolean
        (when-let [name (component-name component)]
                  (and (re-find persistent-chat-component-pattern name)
                       (not (temp-component? component))))))

;; ----------------------------------------------------------------------------
;; Workspace file discovery
;; ----------------------------------------------------------------------------

(defn workspace-files [debug? dir]
      (let [root (io/file dir)]
           (debugf debug? "[dir]" (.getPath root))
           (debugf debug?
                   "  exists:" (.exists root)
                   "directory:" (.isDirectory root)
                   "readable:" (.canRead root))
           (when-not (.isDirectory root)
                     (die! "Directory not found:" (.getPath root)))
           (let [files (->> (or (.listFiles root) [])
                            (filter #(.isFile ^java.io.File %))
                            (filter #(str/ends-with? (.getName ^java.io.File %) ".xml"))
                            (remove #(str/starts-with? (.getName ^java.io.File %) "first-"))
                            (remove #(str/starts-with? (.getName ^java.io.File %) "merged-"))
                            (sort-by #(.getName ^java.io.File %))
                            vec)]
                (debugf debug? "  xml files:" (count files))
                files)))

(defn bookmarks-manager-components [doc]
      (filter #(= "BookmarksManager" (component-name %))
              (components doc)))

(defn group-state-name-option-value [group-state]
      (some (fn [el]
                (when (and (= :option (:tag el))
                           (= "name" (attr el :name)))
                      (attr el :value)))
            (descendants group-state)))

(defn bookmarks-manager-group-names [component]
      (->> (descendants component)
           (filter #(= :GroupState (:tag %)))
           (map group-state-name-option-value)
           (remove nil?)
           distinct
           sort
           vec))

(defn workspace-file-projects [^java.io.File file]
      (try
        (let [doc (parse-xml (.getPath file))
              managers (vec (bookmarks-manager-components doc))]
             {:file     file
              :uuid     (file-uuid file)
              :projects (->> managers
                             (mapcat bookmarks-manager-group-names)
                             distinct
                             sort
                             vec)})
        (catch Exception e
          {:file  file
           :uuid  (file-uuid file)
           :error (str (.getName (class e)) ": " (.getMessage e))})))

(defn matching-workspace-candidate? [project {:keys [projects]}]
      (boolean (some #(= project %) projects)))

(defn find-workspace-candidates [debug? dir project]
      (let [infos (mapv workspace-file-projects (workspace-files debug? dir))]
           (when debug?
                 (doseq [{:keys [uuid error]} (filter :error infos)]
                        (debugf true " " uuid "ERROR" error)))
           (filterv #(matching-workspace-candidate? project %) infos)))

(defn print-candidate-summary! [label project candidates]
      (println label "matches for project" (pr-str project) ":")
      (if (seq candidates)
        (doseq [{:keys [uuid file projects]} candidates]
               (println " " uuid "->" (.getPath ^java.io.File file)
                        "projects:" (pr-str projects)))
        (println "  none")))

(defn select-candidate-by-uuid! [label project dir candidates uuid]
      (let [matches (filterv #(= uuid (:uuid %)) candidates)]
           (cond
             (= 1 (count matches))
             (:file (first matches))

             (empty? matches)
             (do
               (print-candidate-summary! label project candidates)
               (die! "No matching" label "workspace file with UUID:" uuid "in:" dir))

             :else
             (die! "Multiple candidates with UUID:" uuid "in:" dir))))

(defn select-single-candidate! [debug? label project dir candidates selected-uuid]
      (when debug?
            (print-candidate-summary! label project candidates))
      (cond
        selected-uuid
        (select-candidate-by-uuid! label project dir candidates selected-uuid)

        (= 1 (count candidates))
        (:file (first candidates))

        (empty? candidates)
        (die! "No matching" label "workspace file found in:" dir "for project:" project)

        :else
        (do
          (when-not debug?
                    (print-candidate-summary! label project candidates))
          (die! "Multiple matching" label "workspace files found in:" dir
                "for project:" project
                "- use --target-uuid for old/main, or isolate the intended XML."))))

(defn find-workspace-file [debug? label dir project selected-uuid]
      (select-single-candidate!
        debug?
        label
        project
        dir
        (find-workspace-candidates debug? dir project)
        selected-uuid))

(defn find-workspace-files [debug? label dir project]
      (let [candidates (find-workspace-candidates debug? dir project)]
           (when debug?
                 (print-candidate-summary! label project candidates))
           (when (empty? candidates)
                 (die! "No matching" label "workspace files found in:" dir "for project:" project))
           (mapv :file candidates)))

;; ----------------------------------------------------------------------------
;; Merge model
;; ----------------------------------------------------------------------------

(defn element-signature [el]
      [(:tag el)
       (attr el :name)
       (attr el :key)
       (attr el :value)
       (attr el :file)
       (attr el :editor-type-id)])

(defn keyed-entry-id [el]
      (when (= :entry (:tag el))
            (attr el :key)))

(defn file-entry-id [el]
      (when (= :entry (:tag el))
            (when-let [f (attr el :file)]
                      (when (re-find chat-file-pattern f)
                            f))))

(defn append-content [node child]
      (update node :content #(vec (concat (or % []) [child]))))

(defn update-parent-at-path [node path f]
      (if (empty? path)
        [(f node) true]
        (let [sig (first path)
              more (rest path)
              found? (volatile! false)]
             [(update node :content
                      (fn [children]
                          (mapv (fn [child]
                                    (if (and (element? child)
                                             (= sig (element-signature child))
                                             (not @found?))
                                      (let [[child' child-found?] (update-parent-at-path child more f)]
                                           (when child-found?
                                                 (vreset! found? true))
                                           child')
                                      child))
                                children)))
              @found?])))

(defn collect-entry-elements-with-parent-path [component id-fn]
      (let [entries (volatile! [])]
           (letfn [(walk-children [parent parent-path]
                                  (doseq [child (child-elements parent)]
                                         (let [child-path (conj parent-path (element-signature child))]
                                              (when-let [id (id-fn child)]
                                                        (vswap! entries conj {:id          id
                                                                              :element     child
                                                                              :parent-path parent-path}))
                                              (walk-children child child-path))))]
                  (walk-children component []))
           @entries))

(defn collect-entry-elements-by-id [component id-fn]
      (into {}
            (map (fn [{:keys [id element]}] [id element]))
            (collect-entry-elements-with-parent-path component id-fn)))

(defn xml-entry-fingerprint [element]
      (sha-256-string (pr-str element)))

(defn classify-entry-overlap [old-by-id new-by-id]
      (let [old-ids (set (keys old-by-id))
            new-ids (set (keys new-by-id))
            both (sort (set/intersection old-ids new-ids))
            identical (volatile! [])
            conflicting (volatile! [])]
           (doseq [id both]
                  (let [old-fp (xml-entry-fingerprint (get old-by-id id))
                        new-fp (xml-entry-fingerprint (get new-by-id id))]
                       (if (= old-fp new-fp)
                         (vswap! identical conj id)
                         (vswap! conflicting conj {:id id :old-sha old-fp :new-sha new-fp}))))
           {:old-only    (sort (set/difference old-ids new-ids))
            :new-only    (sort (set/difference new-ids old-ids))
            :identical   @identical
            :conflicting @conflicting}))

(defn merge-entry-kind! [{:keys [old-component new-component id-fn label]}]
      (let [old-by-id (collect-entry-elements-by-id old-component id-fn)
            new-by-id (collect-entry-elements-by-id new-component id-fn)
            overlap-report (classify-entry-overlap old-by-id new-by-id)
            existing-ids (volatile! (set (keys new-by-id)))
            old-entries (collect-entry-elements-with-parent-path old-component id-fn)]
           (loop [component new-component
                  entries old-entries
                  added 0
                  skipped 0
                  added-ids []]
                 (if (empty? entries)
                   {:component component
                    :result    (merge {:label     label
                                       :added     added
                                       :skipped   skipped
                                       :added-ids added-ids}
                                      overlap-report)}
                   (let [{:keys [id element parent-path]} (first entries)]
                        (if (@existing-ids id)
                          (recur component (rest entries) added (inc skipped) added-ids)
                          (let [[component' found?]
                                (update-parent-at-path component parent-path #(append-content % element))]
                               (if found?
                                 (do
                                   (vswap! existing-ids conj id)
                                   (recur component' (rest entries) (inc added) skipped (conj added-ids id)))
                                 (recur component (rest entries) added (inc skipped) added-ids)))))))))

(defn merge-component [{:keys [old-component new-component include-editor-files?]}]
      (let [{component-after-keyed :component keyed-result :result}
            (merge-entry-kind! {:old-component old-component
                                :new-component new-component
                                :id-fn         keyed-entry-id
                                :label         "keyed <entry key=\"...\"> items"})
            file-merge
            (when include-editor-files?
                  (merge-entry-kind! {:old-component old-component
                                      :new-component component-after-keyed
                                      :id-fn         file-entry-id
                                      :label         "chat file items"}))]
           (if file-merge
             {:component (:component file-merge)
              :results   [keyed-result (:result file-merge)]}
             {:component component-after-keyed
              :results   [keyed-result]})))

(defn replace-component-in-doc [doc target-name new-component]
      (update doc :content
              (fn [children]
                  (mapv (fn [child]
                            (if (and (element? child)
                                     (= :component (:tag child))
                                     (= target-name (component-name child)))
                              new-component
                              child))
                        children))))

(defn append-component-to-doc [doc component]
      (append-content doc component))

(defn merge-component? [name old-component all-components? include-editor-files?]
      (or all-components?
          (ai-component? old-component)
          (and include-editor-files? (= name "FileEditorManager"))))

(defn merge-old-component [doc new-components-by-name old-component include-editor-files?]
      (let [name (component-name old-component)]
           (if-let [new-component (get new-components-by-name name)]
                   (let [{:keys [component results]}
                         (merge-component {:old-component         old-component
                                           :new-component         new-component
                                           :include-editor-files? include-editor-files?})]
                        {:doc     (replace-component-in-doc doc name component)
                         :result  {:component name :results results}
                         :copied? false})
                   (if (copy-missing-component? old-component)
                     {:doc     (append-component-to-doc doc old-component)
                      :result  {:component name
                                :results   [{:label "whole missing component" :added 1 :skipped 0}]}
                      :copied? true}
                     {:doc doc :result nil :copied? false}))))

(defn merge-xml-docs [{:keys [old-doc new-doc all-components? include-editor-files?]}]
      (let [new-components-by-name (component-map new-doc)]
           (loop [doc new-doc
                  old-components (components old-doc)
                  results []
                  copied 0]
                 (if (empty? old-components)
                   {:doc doc :component-results results :copied-components copied}
                   (let [old-component (first old-components)
                         name (component-name old-component)]
                        (if-not (merge-component? name old-component all-components? include-editor-files?)
                                (recur doc (rest old-components) results copied)
                                (let [{doc' :doc result :result copied? :copied?}
                                      (merge-old-component doc new-components-by-name old-component include-editor-files?)]
                                     (recur doc'
                                            (rest old-components)
                                            (cond-> results result (conj result))
                                            (cond-> copied copied? inc)))))))))

(defn merge-xml-files! [{:keys [old-path new-path out-path all-components? include-editor-files?]}]
      (let [{:keys [doc component-results copied-components]}
            (merge-xml-docs {:old-doc               (parse-xml old-path)
                             :new-doc               (parse-xml new-path)
                             :all-components?       all-components?
                             :include-editor-files? include-editor-files?})]
           (write-xml! doc out-path)
           {:component-results component-results
            :copied-components copied-components}))

(defn merge-many-xml-files! [{:keys [base-path merge-paths out-path include-editor-files? all-components?]}]
      (spit out-path (slurp base-path))
      (let [results (volatile! [])
            copied (volatile! 0)]
           (doseq [merge-path merge-paths]
                  (let [{:keys [copied-components component-results]}
                        (merge-xml-files! {:old-path              merge-path
                                           :new-path              out-path
                                           :out-path              out-path
                                           :all-components?       all-components?
                                           :include-editor-files? include-editor-files?})]
                       (vswap! copied + copied-components)
                       (vswap! results conj {:source merge-path :results component-results})))
           {:copied-components @copied
            :merge-results     @results}))

;; ----------------------------------------------------------------------------
;; Debug helpers
;; ----------------------------------------------------------------------------

(defn component-by-name [doc target-name]
      (some #(when (= target-name (component-name %)) %) (components doc)))

(def file-reference-attr-keys [:file :path :url :filePath])
(def content-attr-keys [:value])
(def chat-ish-text-pattern #"(?i)(ai|chat|conversation|llm|assistant|prompt)")

(defn attr-values-for-keys [component ks]
      (->> (descendants component)
           (mapcat (fn [el]
                       (for [k ks
                             :let [v (attr el k)]
                             :when (string? v)]
                            {:attr k :value v})))
           distinct
           (sort-by :value)
           vec))

(defn short-value [s]
      (let [s (str/replace s #"\s+" " ")]
           (if (> (count s) 160) (str (subs s 0 160) " ...") s)))

(defn debug-chat-file-scan! [debug? label xml-file]
      (when debug?
            (let [doc (parse-xml (.getPath ^java.io.File xml-file))
                  all-file-values (->> (components doc)
                                       (mapcat #(attr-values-for-keys % file-reference-attr-keys))
                                       distinct
                                       (sort-by :value)
                                       vec)
                  chat-files (filterv #(re-find chat-ish-text-pattern (:value %)) all-file-values)
                  inline-chat-values (->> (components doc)
                                          (mapcat #(attr-values-for-keys % content-attr-keys))
                                          (filter #(re-find chat-ish-text-pattern (:value %)))
                                          vec)
                  fem (component-by-name doc "FileEditorManager")
                  fem-values (if fem (attr-values-for-keys fem file-reference-attr-keys) [])
                  fem-chat-values (filterv #(re-find chat-ish-text-pattern (:value %)) fem-values)]
                 (debugf true "[chat/file scan]" label (.getName ^java.io.File xml-file))
                 (debugf true "  file/path/url values in whole XML:" (count all-file-values))
                 (debugf true "  chat-ish file/path/url values in whole XML:" (count chat-files))
                 (doseq [{:keys [attr value]} chat-files]
                        (debugf true "   " attr ":" value))
                 (debugf true "  file/path/url values in FileEditorManager:" (count fem-values))
                 (debugf true "  chat-ish file/path/url values in FileEditorManager:" (count fem-chat-values))
                 (doseq [{:keys [attr value]} fem-chat-values]
                        (debugf true "   FileEditorManager" attr ":" value))
                 (debugf true "  inline chat-like value attributes:" (count inline-chat-values))
                 (doseq [{:keys [attr value]} (take 5 inline-chat-values)]
                        (debugf true "   inline" attr ":" (short-value value))))))

;; ----------------------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------------------

(defn print-merge-plan! [old-file new-files out-file]
      (println)
      (println "Merge plan:")
      (println "  base:" (.getPath ^java.io.File old-file))
      (doseq [f new-files]
             (let [{:keys [same? a-sha b-sha]} (file-content-relation old-file f)]
                  (println "  merge:" (.getPath ^java.io.File f))
                  (when (= (.getName ^java.io.File old-file)
                           (.getName ^java.io.File f))
                        (println "    same filename as base; content:" (if same? "identical" "different"))
                        (println "    base sha256:" a-sha)
                        (println "    merge sha256:" b-sha))))
      (println "  workspace copy target:" (.getParent ^java.io.File out-file))
      (println "  merged main xml:" (.getPath ^java.io.File out-file)))

(defn print-summary! [project old-file new-files out-file copied-components merge-results]
      (println "Merged:")
      (println " " (.getPath ^java.io.File old-file))
      (doseq [f new-files]
             (println " " (.getPath ^java.io.File f)))
      (println "into merged workspace:")
      (println " " (.getParent ^java.io.File out-file))
      (println "main merged XML:")
      (println " " (.getPath ^java.io.File out-file))
      (println)
      (println "Summary:")
      (println "  project:" project)
      (println "  copied new workspace files into:" (.getParent ^java.io.File out-file))
      (println "  merged source workspace files:" (count new-files))
      (println "  copied whole missing AI-looking components:" copied-components)
      (doseq [{:keys [source results]} merge-results
              {:keys [component results]} results
              {:keys [label added skipped]} results
              :when (pos? added)]
             (println " " component "-" label "- added:" added "skipped:" skipped "- from:" source)))

(defn -main [& args]
      (let [{:keys [project old-dir new-dir debug? target-uuid]} (parse-main-args args)
            old-file (find-workspace-file debug? "old/main" old-dir project target-uuid)
            new-files (find-workspace-files debug? "new" new-dir project)
            out-dir (io/file "merged")
            out-file (merged-output-file out-dir project old-file)]
           (copy-workspace-dir! new-dir out-dir)
           (when debug?
                 (print-merge-plan! old-file new-files out-file))
           (let [{:keys [copied-components merge-results]}
                 (merge-many-xml-files! {:base-path             (.getPath ^java.io.File old-file)
                                         :merge-paths           (mapv #(.getPath ^java.io.File %) new-files)
                                         :out-path              (.getPath ^java.io.File out-file)
                                         :all-components?       false
                                         :include-editor-files? true})]
                (print-summary! project old-file new-files out-file copied-components merge-results))))

(apply -main *command-line-args*)