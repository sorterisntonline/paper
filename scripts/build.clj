#!/usr/bin/env bb
(ns build
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn extract-meta [tex-path]
  (let [content (slurp (io/file (str tex-path)))
        title-match (re-find #"(?s)\\title\{(.*?)\}" content)
        title (if title-match
                (-> (second title-match)
                    (str/replace #"\s+" " ")
                    (str/trim))
                (-> tex-path fs/file-name str (str/replace #"\.tex$" "")))]
    {:title title}))


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
  (let [html (->> ["<!doctype html>"
                   "<html lang=\"en\">"
                   "<head>"
                   "  <meta charset=\"utf-8\">"
                   "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                   "  <title>LaTeX Blog</title>"
                   "  <style>body{font:16px/1.5 -apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;color:#222} h1{font-size:1.8rem} ul{list-style:none;padding:0} li{margin:0.5rem 0} .footer{margin-top:2rem;color:#666;font-size:0.9rem} a{color:#1558d6;text-decoration:none} a:hover{text-decoration:underline}</style>"
                   "</head>"
                   "<body>"
                   "  <h1>LaTeX Blog</h1>"
                   "  <ul>"
                   (->> items
                        (map (fn [{:keys [title pdf-rel]}]
                               (format "    <li><a href=\"%s\">%s</a></li>"
                                       pdf-rel title)))
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
                            (let [{:keys [title]} (extract-meta main)
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
                                {:slug slug :title title :pdf-rel (str (fs/file-name dest))})))))]
      (spit (str (fs/path PUBLIC_DIR "index.html")) (generate-index items))
      (println (format "Built %d posts into 'public/' and generated index.html" (count items))))))

(-main)
