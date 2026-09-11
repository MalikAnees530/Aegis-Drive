# Contributing to Aegis Drive

Aegis Drive is a collaborative final-year project. **Every team member has write
access and is an equal maintainer** — you do not need anyone's permission to
start work, and you never need to fork this repository.

Outside contributors are welcome too; see [For outside contributors](#for-outside-contributors).

---

## Getting set up

```bash
git clone https://github.com/MalikAnees530/Aegis-Drive.git
cd Aegis-Drive
cp local.properties.example local.properties   # then fill in your own values
```

You will also need your own `app/google-services.json` from the Firebase console.
Both files are gitignored — see [Security rules](#security-rules) below.

**Requirements:** Android Studio (Hedgehog+), **JDK 17+** (AGP 8.3 requires it),
and a device or emulator on **API 26+**.

Verify your setup builds before you change anything:

```bash
./gradlew assembleDebug
```

---

## The workflow

As a team member with write access, work directly in this repository:

1. **Sync `main`**
   ```bash
   git checkout main && git pull
   ```
2. **Create a branch** off `main`:
   ```bash
   git checkout -b feat/voice-alerts
   ```
   Prefix with `feat/`, `fix/`, `docs/`, `refactor/`, `test/` or `chore/`.
3. **Commit** in small, focused steps using [Conventional Commits](https://www.conventionalcommits.org/):
   ```
   feat(monitor): add head-pose gating to drowsiness alerts
   fix(chat): stop TTS leaking across fragment recreation
   docs(readme): correct the required JDK version
   ```
4. **Push your branch** and open a Pull Request:
   ```bash
   git push -u origin feat/voice-alerts
   ```
5. **CI runs automatically** — the Android CI workflow builds a debug APK and
   runs the unit tests on every PR. Make sure it's green.
6. **Merge** once CI passes and the review policy below is satisfied.

> Never force-push to `main`, and never push a branch that contains real
> credentials in its history.

---

## Review policy

<!--
  TEAM DECISION — please fill this in.

  This is a genuine trade-off and it is your team's call, not a technical default:

  • "Anyone may merge their own PR once CI is green"
      → fastest; best while you are all building different screens in parallel.
      → risk: mistakes reach `main` unreviewed right before a demo.

  • "Every PR needs one approval from another maintainer"
      → catches bugs and spreads knowledge of the codebase across the team,
        which matters when a supervisor asks any member to explain any part.
      → risk: someone is blocked waiting if a teammate is unavailable.

  • A hybrid, e.g. "self-merge for docs/ and chore/; one approval for anything
    touching app/src or .github/".

  Replace this comment with 4–8 lines stating the rule you agree on, and say
  who may merge to `main`.
-->

_To be agreed by the team — see the note above._

---

## Code standards

- Follow standard **Kotlin coding conventions**; `.editorconfig` is committed, so
  let your IDE apply it.
- **Test CameraX, MediaPipe, and SMS changes on a physical device.** The emulator
  does not faithfully reproduce camera timing, and it cannot send SMS at all.
- Match the **dark-first "Premium Minimal"** design system: reference the shared
  design tokens and styles (`res/values/`, `res/values-night/`, `AegisMotion`)
  rather than hardcoding colors, sizes, or fonts.
- Keep safety-critical inference **on-device**. Camera frames must never be
  uploaded.

---

## Security rules

These are non-negotiable, because a leak here exposes real user data:

1. **Never commit** `local.properties`, `google-services.json`, `*.jks`, or any
   keystore/`.env` file. They are all in `.gitignore` — do not override it with
   `git add -f`.
2. **Never paste an API key** into source, a commit message, an issue, or a PR
   description. Keys are injected at build time via `BuildConfig`.
3. CI reads its values from **GitHub Actions secrets** (`GROK_API_KEY`,
   `GOOGLE_SERVICES_JSON`). Add new secrets there, never to a tracked file.
4. If you ever commit a secret by accident, **rotate the key immediately** —
   revoking it matters far more than deleting the commit. Then tell the team.

Vulnerabilities should be reported privately — see [SECURITY.md](SECURITY.md).

---

## For outside contributors

If you don't have write access:

1. **Fork** the repository and create your branch there.
2. Open a Pull Request against `main`.
3. Describe what you changed and how you tested it on a physical device.

Bug reports and feature ideas are equally welcome via the
[Issues](https://github.com/MalikAnees530/Aegis-Drive/issues) tab — please use
the provided templates.
