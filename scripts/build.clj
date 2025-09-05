#!/usr/bin/env bb
(ns build
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDate Instant ZoneId)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(def ROOT (fs/absolutize "."))
(def POSTS_DIR (fs/path ROOT "posts"))
(def PUBLIC_DIR (fs/path ROOT "public"))

(defn find-posts []
  (when (fs/directory? POSTS_DIR)
    (->> (fs/list-dir POSTS_DIR)
         (filter fs/directory?)
         (map (fn [p]
                (let [main (fs/path p "main.tex")]
                  (when (fs/exists? main)
                    {:slug (fs/file-name p)
                     :main main}))))
         (remove nil?)
         (sort-by :slug))))

(defn- re-find1 [re s]
  (when-let [m (re-find re s)]
    (if (vector? m) (second m) m)))

(defn extract-meta [tex-path]
  (let [content (slurp (io/file (str tex-path)))
        title (some-> (re-find1 #"(?s)\\title\{(.*?)\}" content)
                      (str/replace #"\s+" " ")
                      (str/trim))
        date-str (some-> (re-find1 #"(?s)\\date\{(.*?)\}" content)
                         (str/replace #"\s+" " ")
                         (str/trim))
        title (or title (-> tex-path fs/file-name str (str/replace #"\.tex$" "")))
        ds (let [ds (or date-str "")
                 ds2 (-> ds (str/lower-case) (str/replace #"\\" ""))]
             (if (or (str/blank? ds) (= ds2 "today"))
               (-> (fs/last-modified-time tex-path)
                   (.toInstant)
                   (LocalDate/ofInstant (ZoneId/systemDefault))
                   (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd")))
               date-str))]
    {:title title :date ds}))

(def date-formats
  [(DateTimeFormatter/ofPattern "yyyy-MM-dd")
   (DateTimeFormatter/ofPattern "yyyy/MM/dd")
   (DateTimeFormatter/ofPattern "dd-MM-yyyy")
   (DateTimeFormatter/ofPattern "MMM d, yyyy")
   (DateTimeFormatter/ofPattern "MMMM d, yyyy")])

(defn parse-date [s]
  (some (fn [^DateTimeFormatter fmt]
          (try
            (LocalDate/parse s fmt)
            (catch DateTimeParseException _ nil)))
        date-formats))

(defn ensure-clean-dir [p]
  (when (fs/exists? p)
    (fs/delete-tree p))
  (fs/create-dirs p))

(defn compile-with-tectonic [tex-path outdir]
  (println "Running tectonic for" (str tex-path))
  (let [res @(p/process {:out :inherit :err :inherit}
                        "tectonic" "-X" "compile"
                        "--keep-logs" "--keep-intermediates"
                        "--outdir" (str outdir)
                        (str tex-path))]
    (when-not (zero? (:exit res))
      (throw (ex-info "Tectonic failed" {:exit (:exit res)})))))

(defn generate-index [items]
  (let [items (->> items
                   (sort-by (fn [{:keys [date]}]
                              (or (parse-date date) (LocalDate/ofEpochDay 0)))
                            #(compare %2 %1))) ; descending
        html (->> ["<!doctype html>"
                   "<html lang=\"en\">"
                   "<head>"
                   "  <meta charset=\"utf-8\">"
                   "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                   "  <title>LaTeX Blog</title>"
                   "  <style>body{font:16px/1.5 -apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;color:#222} h1{font-size:1.8rem} ul{list-style:none;padding:0} li{margin:0.5rem 0} .date{color:#666;font-size:0.9rem;margin-left:0.5rem} .footer{margin-top:2rem;color:#666;font-size:0.9rem} a{color:#1558d6;text-decoration:none} a:hover{text-decoration:underline}</style>"
                   "</head>"
                   "<body>"
                   "  <h1>LaTeX Blog</h1>"
                   "  <ul>"
                   (->> items
                        (map (fn [{:keys [title date pdf-rel]}]
                               (format "    <li><a href=\"%s\">%s</a><span class=\"date\">%s</span></li>"
                                       pdf-rel title date)))
                        (str/join "\n"))
                   "  </ul>"
                   "  <div class=\"footer\">Powered by Tectonic + GitHub Actions</div>"
                   "</body>"
                   "</html>"]
                  (str/join "\n"))]
    html))

(defn -main [& _]
  (fs/create-dirs PUBLIC_DIR)
  (let [posts (find-posts)]
    (when (empty? posts)
      (println "No posts found under 'posts/*/main.tex'.")
      (System/exit 0))
    (let [items (->> posts
                     (map (fn [{:keys [slug main]}]
                            (let [{:keys [title date]} (extract-meta main)
                                  tmp-out (fs/path PUBLIC_DIR slug)]
                              (ensure-clean-dir tmp-out)
                              (compile-with-tectonic main tmp-out)
                              (let [src1 (fs/path tmp-out "main.pdf")
                                    src2 (fs/path tmp-out (str (-> main fs/file-name str (str/replace #"\.tex$" "")) ".pdf"))
                                    dest (fs/path PUBLIC_DIR (str slug ".pdf"))
                                    src (cond
                                          (fs/exists? src1) src1
                                          (fs/exists? src2) src2
                                          :else nil)]
                                (when-not src
                                  (throw (ex-info (str "PDF not found for " main) {})))
                                (when (fs/exists? dest) (fs/delete dest))
                                (fs/move src dest {:replace-existing true})
                                (fs/delete-tree tmp-out)
                                {:slug slug :title title :date date :pdf-rel (str (fs/file-name dest))})))))]
      (spit (fs/path PUBLIC_DIR "index.html") (generate-index items))
      (println (format "Built %d posts into 'public/' and generated index.html" (count items))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))

