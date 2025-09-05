# LaTeX Blog

A minimal repo template to write LaTeX documents and automatically publish compiled PDFs to GitHub Pages with an index.

## How it works

- Put each post in its own folder under `posts/` with a `main.tex` file.
- On push to `main`, GitHub Actions:
  - Sets up Tectonic (a modern TeX engine) and Babashka (Clojure scripting).
  - Compiles each `posts/*/main.tex` to a PDF.
  - Generates `public/index.html` listing all PDFs (title + date).
  - Publishes the `public/` folder to GitHub Pages.

## Requirements

- Enable GitHub Pages for the repo with "GitHub Actions" as the source.
- Default branch named `main` (or adjust the workflow trigger).

## Add a new post

1. Create a folder under `posts/` (the folder name is the slug):
   - Example: `posts/my-first-post/main.tex`
2. Include a `\title{...}` and optionally `\date{YYYY-MM-DD}` in your LaTeX preamble.
   - If `\date{}` is omitted or `\today`, the file's modified time is used.
3. Commit and push to `main`.

Within a few minutes, your Pages site will update. The PDF will be available at `/my-first-post.pdf` and linked from the index.

## Local build (optional)

If you want to preview locally:

```bash
bb --version                  # ensure babashka is installed
tectonic --version            # ensure tectonic is installed
bb scripts/build.clj
open public/index.html
```

Note: The CI uses the official `setup-tectonic` and `setup-babashka` actions to install tools on runners; locally you must install them yourself (e.g., `brew install babashka tectonic`).

## Customization

- Edit `scripts/build.clj` to tweak index styling or metadata parsing.
- Add your own LaTeX packages to each `main.tex` as needed. Tectonic fetches missing packages automatically during CI.
