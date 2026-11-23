# Generated files

Written by the bot after every merge to `main`, never by hand and never merged into `main`:

- `docs/` — the website, served by GitHub Pages from this branch
- `ROADMAP.md` — the roadmap, from `coverage/roadmap/` and `coverage/measures.json` on `main`
- `scoreboard/all.json` — main's jar scoreboard; `scoreboard/points.json` — the points of every measure marked `recordedBy`

`bench/generated.py` on `main` writes them. They live here because `main` requires pull requests to be up to date, and a bot commit to `main` put every open pull request behind.
