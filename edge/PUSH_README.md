# PUSH_README — publishing the ECO JOURNAL app repo (one-time, later)

## Honest status (25 Oct 2026, lab)

- This folder IS a git repo (initialised in the lab, first commit
  included) but it has **NO remote and NOTHING has been pushed**.
  `gh` on this VM is not logged into any GitHub host, and there is
  no saved GitHub login anywhere here. No remote URL has been
  invented and no repo has been created through an unauthenticated
  call — that would fail and, worse, would pretend to be done.
- The pilot is NOT blocked on this: the app ships with a bundled
  seed (`app/src/main/assets/seed/`, a verbatim copy of `edge/out/`)
  and works fully offline on first launch. Pushing only unlocks
  (a) over-the-air edge updates and (b) cloud APK builds.

## The single future session (owner, logged in, ~5 minutes)

Run these — in this order — from this folder:

```bash
cd ~/workspace/goals/eco-journal/app   # this folder = repo root

# 1. Pick up anything added since the lab commit (builder files, etc.)
git add -A
git status          # eyeball: no google-services.json, no *.env, no *.jks
git commit -m "ECO JOURNAL app Phase 1 + setup" || true

# 2. Log in once (browser flow, on the owner's machine):
gh auth login

# 3. Create the repo and push (public, free — required for free Pages):
gh repo create eco-journal-app --public --source=. --push
```

If `gh` is not available on that machine, the equivalent is:

```bash
git remote add origin https://github.com/<user>/eco-journal-app.git
git push -u origin main
```

## After the push (same session, 2 toggles)

1. **Pages source:** repo → Settings → Pages → Build and
   deployment → Source → **GitHub Actions**. The `pages.yml`
   workflow then deploys `edge/out/` on every push touching `edge/`.
   The edge URL becomes `https://<user>.github.io/eco-journal-app`
   (no `/out` suffix).
2. **Point the app at the edge:** in
   `app/src/main/java/com/ecojournal/app/data/EdgeRepository.kt`,
   set `EdgeConfig.BASE_URL` to that URL. From then on, WorkManager
   sync and FCM data-message fetches use the live edge; the bundled
   seed stays as the offline fallback and first-launch content.

## What is pushed, what is never pushed

- Pushed: Android source, `edge/out/` snapshot (2.5 MB, real
  approved content), bundled assets copy, workflows, docs, fonts.
- Never pushed (gitignored, verified absent): `google-services.json`,
  any `*.env`, any bot token, any signing key. If `git status`
  ever shows one of those, STOP and do not push.
