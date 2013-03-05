;; ## Import utility
;;
;; This is a command-line tool for importing data into PuppetDB.  It expects
;; as input a tarball generated by the PuppetDB `export` command-line tool.

(ns com.puppetlabs.puppetdb.cli.import
  (:require [fs.core :as fs]
            [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.archive :as archive]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import  [com.puppetlabs.archive TarGzReader]
            [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:use [com.puppetlabs.utils :only (cli! num-cpus)]
        [com.puppetlabs.puppetdb.cli.export :only [export-root-dir export-metadata-file-name]]
        [com.puppetlabs.concurrent :only [producer consumer work-queue->seq]]))

(def cli-description "Import PuppetDB catalog data from a backup file")

(defn parse-metadata
  "Parses the export metadata file to determine, e.g., what versions of the
  commands should be used during import."
  [tarball]
  {:pre  [(fs/exists? tarball)]
   :post [(map? %)
          (contains? % :command-versions)]}
  (let [metadata-path (.getPath (io/file export-root-dir export-metadata-file-name))]
    (with-open [tar-reader (archive/tarball-reader tarball)]
      (when-not (archive/find-entry tar-reader metadata-path)
        (throw (IllegalStateException.
                 (format "Unable to find export metadata file '%s' in archive '%s'"
                   metadata-path
                   tarball))))
      (json/parse-string (archive/read-entry-content tar-reader) true))))

(defn submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port command-version catalog-payload]
  {:pre  [(string?  puppetdb-host)
          (integer? puppetdb-port)
          (integer? command-version)
          (string?  catalog-payload)]}
  (let [result (command/submit-command-via-http!
                  puppetdb-host puppetdb-port
                  "replace catalog" command-version
                  catalog-payload)]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))


(defn get-tar-entry-with-content
  ;; TODO: docs, preconds
  [tar-reader]
  (let [tar-entry (archive/next-entry tar-reader)]
    (if (nil? tar-entry)
      nil
      {:tar-entry tar-entry
       :content   (archive/read-entry-content tar-reader)})))

(defn process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [host port metadata {:keys [content tar-entry]}]
  {:pre  [(string? content)
          (instance? TarArchiveEntry tar-entry)
          (string? host)
          (integer? port)
          (map? metadata)]}
  (let [path    (.getName tar-entry)
        pattern (str "^" (.getPath (io/file export-root-dir "catalogs" ".*\\.json")) "$")]
    (if (re-find (re-pattern pattern) path)
      (do
        ;; NOTE: these submissions are async and we have no guarantee that they
        ;;   will succeed.  We might want to add something at the end of the import
        ;;   that polls puppetdb until the command queue is empty, then does a
        ;;   query to the /nodes endpoint and shows the set difference between
        ;;   the list of nodes that we submitted and the output of that query
        (submit-catalog host port
          (get-in metadata [:command-versions :replace-catalog])
          content)
        {:type    :catalog
         :message (format "Submitted catalog from archive entry '%s'" path)})
      {:type    :unknown
       :message "Unrecognized tar entry; skipping"})))

(defn -main
  [& args]
  (let [specs       [["-i" "--infile" "Path to backup file (required)"]
                     ["-H" "--host" "Hostname of PuppetDB server" :default "localhost"]
                     ["-p" "--port" "Port to connect to PuppetDB server" :default 8080]]
        required    [:infile]
        [{:keys [infile host port]} _] (cli! args specs required)
        metadata    (parse-metadata infile)]
;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (with-open [tar-reader (archive/tarball-reader infile)]
      (let [p       (producer #(get-tar-entry-with-content tar-reader) 1 5)
            c       (consumer p #(process-tar-entry host port metadata %) (num-cpus))
            results (work-queue->seq (:result-queue c))]
        (doseq [{:keys [type message]} results]
          (condp = type
            :catalog (log/info message)
            nil))))))
