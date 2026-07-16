# Git Workflow Guide

How we branch, open pull requests, and promote work from feature/bug branches to production.

## Branch model

| Branch | Role |
|--------|------|
| `main` | Production |
| `dev` | Integration |
| `feature/_root` | Parent for feature branches |
| `bugs/_root` | Parent for bug branches |

Promotion path:

```text
feature/<name>  ──PR──▶  feature/_root  ──PR──▶  dev  ──PR──▶  main
bugs/<name>     ──PR──▶  bugs/_root     ──PR──▶  dev  ──PR──▶  main
```

## Contents

1. [Create a feature or bug branch](#1-create-a-feature-or-bug-branch)
2. [Open a PR into `_root`](#2-open-a-pr-into-_root)
3. [Promote `_root` to `dev` and `main`](#3-promote-_root-to-dev-and-main)
4. [Sync between feature and bug work](#4-sync-between-feature-and-bug-work)
5. [Quick reference](#5-quick-reference)

---

## 1. Create a feature or bug branch

### Start from the correct parent

**Feature work:**

```bash
git checkout feature/_root
git pull origin feature/_root
```

**Bug work:**

```bash
git checkout bugs/_root
git pull origin bugs/_root
```

### Create the branch

**Feature example:**

```bash
git checkout -b feature/login-page
```

**Bug example:**

```bash
git checkout -b bugs/fix-overlay-glitch
```

### Commit and push

```bash
# edit files
git status
git add <files>
git commit -m "Describe what you changed"

git push -u origin feature/login-page
# or
git push -u origin bugs/fix-overlay-glitch
```

---

## 2. Open a PR into `_root`

### On GitHub

| Field | Feature | Bug |
|-------|---------|-----|
| **Base** | `feature/_root` | `bugs/_root` |
| **Compare** | `feature/<name>` | `bugs/<name>` |

Fill in title and description, create the PR, review, and merge.

### Clean up after merge

```bash
# delete remote branch
git push origin --delete feature/login-page
# or
git push origin --delete bugs/fix-overlay-glitch

# delete local branch
git branch -d feature/login-page
git branch -d bugs/fix-overlay-glitch
```

---

## 3. Promote `_root` to `dev` and `main`

### Merge `_root` into `dev`

On GitHub:

- **Base:** `dev`
- **Compare:** `feature/_root` or `bugs/_root`

Create PR, review, and merge.

Update locally:

```bash
git checkout dev
git pull origin dev
```

### Merge `dev` into `main`

On GitHub:

- **Base:** `main`
- **Compare:** `dev`

Create PR, review, and merge.

Update locally:

```bash
git checkout main
git pull origin main
```

---

## 4. Sync between feature and bug work

### Pull bug fixes into a feature branch

If you are on `feature/login-page` and need fixes from `bugs/_root`:

```bash
git checkout feature/login-page
git pull origin feature/login-page   # ensure up to date

git pull origin bugs/_root           # bring in bug fixes
```

Resolve any merge conflicts, then commit if needed.

### Pull feature changes into a bug branch

If you are on `bugs/fix-overlay-glitch` and need changes from `feature/_root`:

```bash
git checkout bugs/fix-overlay-glitch
git pull origin bugs/fix-overlay-glitch

git pull origin feature/_root
```

Resolve conflicts, then commit if needed.

### Rules of thumb

- Regularly pull from `_root` branches (`feature/_root`, `bugs/_root`) and `dev` into your working branch.
- Avoid pulling directly from random sub-branches unless you know exactly what is in them.

---

## 5. Quick reference

### Feature work

```bash
git checkout feature/_root && git pull
git checkout -b feature/<name>
# commit + push
git push -u origin feature/<name>
```

PRs:

1. `feature/<name>` → `feature/_root`
2. `feature/_root` → `dev`
3. `dev` → `main`

### Bug work

```bash
git checkout bugs/_root && git pull
git checkout -b bugs/<name>
# commit + push
git push -u origin bugs/<name>
```

PRs:

1. `bugs/<name>` → `bugs/_root`
2. `bugs/_root` → `dev`
3. `dev` → `main`
