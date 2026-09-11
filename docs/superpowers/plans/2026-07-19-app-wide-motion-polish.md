# App-Wide Motion Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Apply the `frontend-design` skill for any judgment call on motion feel.

**Goal:** Complete and unify the app's motion system so all 10 screens feel modern and cohesive — consistent tab transitions on every bottom-nav destination, and tactile press feedback on every button — building only on the existing `AegisMotion` + `res/anim` system.

**Architecture:** Android Material 3 XML views, no Compose, `findViewById`. Motion primitives live in `AegisMotion.kt` and `res/anim/`. Activity↔activity transitions already exist globally (`Animation.Aegis.Activity` via `windowAnimationStyle`). This plan adds the two missing fragment transitions (Navigate, Monitor) and rolls out the already-written-but-unused `pressScale` to buttons app-wide. Verification is build + on-device observation (no unit tests exist for animations).

**Tech Stack:** Kotlin, Material Components 1.11.0 (`com.google.android.material.transition.MaterialFadeThrough`), AndroidX Fragment + Navigation Component, Gradle (`gradlew.bat`), adb to physical TECNO KJ7.

## Global Constraints

- Everything is in package `com.malik.aegisdrive`, so `AegisMotion` needs **no import** in these files.
- Do NOT change any business logic, Firebase/Groq/CameraX/TFLite/MediaPipe behavior, the dark palette, or any layout IDs. Motion only.
- Do NOT alter the existing, working transitions: Home & Chat already set `enterTransition = MaterialFadeThrough()` / `exitTransition = MaterialFadeThrough()` (`HomeFragment.kt:41-42`, `ChatFragment.kt:87-88`); the global activity transition style `Animation.Aegis.Activity` (`styles.xml:147-152`) is already correct. Leave them as-is.
- `MonitorFragment.kt` edits are limited to ADDING an `onCreate` override that sets an enter transition (and the `applyPressToButtons` call in Task 4). No detection/logic changes. Its 20 frozen view IDs stay frozen.
- `pressScale` (`AegisMotion.kt:83-97`) returns `false` from its touch listener so normal click handling still fires — do not change that.
- Every build shell first: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (bash) or `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell). Build: `./gradlew.bat assembleDebug --console=plain` → success = ends `BUILD SUCCESSFUL`.
- adb: `C:\Users\L13\AppData\Local\Android\Sdk\platform-tools\adb.exe`; device serial `115333741E052855`; the device is logged in and boots to Home. Use PowerShell for adb (Git Bash mangles `/sdcard/...` paths). Screenshot: `<adb> shell screencap -p /sdcard/x.png; <adb> pull /sdcard/x.png <scratch>\x.png`. Scratch: `C:\Users\L13\AppData\Local\Temp\claude\C--Users-L13-Documents-doc-AegisDriveApp\3049755c-4c56-4bf5-bbdb-b3ea36707a89\scratchpad`. Bottom nav (1080x2436 device): tap y≈2230, x≈135(Home)/405(Navigate)/675(Monitor)/945(Chat).
- Commit after each task; end every commit message with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## Verification model (read once)

Animations have no unit tests. Each task's "test" is: (1) `BUILD SUCCESSFUL`; (2) install (`<adb> install -r app\build\outputs\apk\debug\app-debug.apk`) and observe the specific motion on-device via screenshots / visual check; (3) no crash and no behavior regression on the touched screens.

## What already exists (do NOT re-add)

- Activity transitions: global slide+fade forward/back (`Animation.Aegis.Activity`).
- Fragment transitions: Home, Chat (`MaterialFadeThrough`).
- Entrance animations: `layoutAnimation` on fragment_home, fragment_monitor, activity_login, activity_signup, activity_forgot_password, activity_settings, activity_edit_profile, activity_session_history; bespoke reveal on Splash; programmatic `AegisMotion.entrance` on Chat bubbles; `countUp` + `progressTo` on Home.
- Navigate is a WebView with its own CSS entrance keyframes.

---

### Task 1: Add `applyPressToButtons` helper to AegisMotion

**Files:**
- Modify: `app/src/main/java/com/malik/aegisdrive/AegisMotion.kt` (add one function)

**Interfaces:**
- Produces: `fun applyPressToButtons(root: View)` — recursively finds every `com.google.android.material.button.MaterialButton` under `root` and applies `pressScale` to it. Consumed by Task 4.

- [ ] **Step 1: Add the helper.** In `AegisMotion.kt`, add this function inside the `object AegisMotion { ... }` block, immediately after the existing `pressScale` function (after its closing `}` on line 97, before the object's final `}`):

```kotlin

    /**
     * Walk the view tree under [root] and give every MaterialButton the subtle
     * press-scale feedback. One call wires tactile feedback for a whole screen
     * without per-button boilerplate. Safe to call once per screen after inflation.
     */
    fun applyPressToButtons(root: View?) {
        root ?: return
        when (root) {
            is com.google.android.material.button.MaterialButton -> pressScale(root)
            is ViewGroup -> for (i in 0 until root.childCount) applyPressToButtons(root.getChildAt(i))
        }
    }
```

- [ ] **Step 2: Build.**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (`View` and `ViewGroup` are already imported in this file; `MaterialButton` is referenced fully-qualified so needs no import.)

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/com/malik/aegisdrive/AegisMotion.kt
git commit -m "feat(motion): add AegisMotion.applyPressToButtons tree helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add fragment transition to Navigate tab

**Files:**
- Modify: `app/src/main/java/com/malik/aegisdrive/NavigateFragment.kt`

**Interfaces:**
- Consumes: nothing. Produces: nothing for later tasks.

- [ ] **Step 1: Add the MaterialFadeThrough import.** In `NavigateFragment.kt`, add this line to the import block (e.g. immediately after `import com.google.android.gms.location.*` on line 33):

```kotlin
import com.google.android.material.transition.MaterialFadeThrough
```

- [ ] **Step 2: Set the transitions in `onCreate`.** `NavigateFragment` currently has no `onCreate` override. Add one at the top of the class body (immediately after the field declarations, before `onCreateView` / the permission launcher — placement inside the class is what matters). This mirrors `HomeFragment.kt:41-42` exactly:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }
```

(`android.os.Bundle` is already imported at line 15.)

- [ ] **Step 3: Build.**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Device check.** Install; from Home tap the Navigate tab, then tap another tab and back. Expected: switching to/from Navigate now cross-fades like Home/Chat (no hard cut); the map still loads and search/controls still work.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/malik/aegisdrive/NavigateFragment.kt
git commit -m "feat(motion): MaterialFadeThrough transition on Navigate tab

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Add enter transition to Monitor tab

**Files:**
- Modify: `app/src/main/java/com/malik/aegisdrive/MonitorFragment.kt`

**Interfaces:**
- Consumes: nothing. Produces: nothing for later tasks.

**Note:** Monitor sets **enter only** (no exit transition). Entering the tab fades the HUD in for cohesion with the other tabs; leaving is left as a hard cut so the live-camera teardown never fades mid-frame. This is a deliberate difference from Home/Chat/Navigate.

- [ ] **Step 1: Add the MaterialFadeThrough import.** In `MonitorFragment.kt`, add to the import block (e.g. after `import com.google.android.material.card.MaterialCardView` on line 36):

```kotlin
import com.google.android.material.transition.MaterialFadeThrough
```

- [ ] **Step 2: Add an `onCreate` override that sets the enter transition.** `MonitorFragment` currently has `onCreateView` but no `onCreate`. Add this override immediately BEFORE the existing `onCreateView` (around line 128):

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enter-only: fade the HUD in for tab cohesion; no exit transition so the
        // live camera preview is never faded during teardown. No logic touched.
        enterTransition = MaterialFadeThrough()
    }
```

(`android.os.Bundle` is already imported at line 16.)

- [ ] **Step 3: Build.**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Device check (watch the camera).** Install; from Home tap the Monitor tab. Expected: the HUD + camera preview fade in smoothly (no hard cut, no black flash that lingers). Tap BEGIN MONITORING → confirm monitoring still starts (LIVE/SECURE, metrics update). Switch away to another tab and back to Monitor twice — confirm the camera re-binds cleanly each time with the fade-in and no frozen/blank frame. If the camera shows a persistent black flash on entry, remove the `enterTransition` line and report it (fallback: Monitor keeps only its existing XML entrance).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/malik/aegisdrive/MonitorFragment.kt
git commit -m "feat(motion): enter fade-through on Monitor tab (enter-only, camera-safe)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Roll out press feedback to buttons across all screens

**Files (add one call each):**
- Modify: `app/src/main/java/com/malik/aegisdrive/LoginActivity.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/SignUpActivity.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/ForgotPasswordActivity.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/SettingsActivity.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/EditProfileActivity.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/HomeFragment.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/ChatFragment.kt`
- Modify: `app/src/main/java/com/malik/aegisdrive/MonitorFragment.kt`

**Interfaces:**
- Consumes: `AegisMotion.applyPressToButtons(root: View)` from Task 1.

- [ ] **Step 1: Activities — add the call after `setContentView`.** In EACH of `LoginActivity.kt`, `SignUpActivity.kt`, `ForgotPasswordActivity.kt`, `SettingsActivity.kt`, `EditProfileActivity.kt`, find the `setContentView(R.layout.…)` line inside `onCreate` and add IMMEDIATELY AFTER it:

```kotlin
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))
```

- [ ] **Step 2: Fragments — add the call at the end of `onViewCreated`.** In EACH of `HomeFragment.kt`, `ChatFragment.kt`, `MonitorFragment.kt`, find `override fun onViewCreated(view: View, savedInstanceState: Bundle?)` and add as the LAST statement in that method body:

```kotlin
        AegisMotion.applyPressToButtons(view)
```

(For `MonitorFragment`, this gives `btnStopMonitor` and `btnMuteAlarm` press feedback; it walks the current `view`, so it is independent of Task 3.)

- [ ] **Step 3: Build.**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (All eight files are in package `com.malik.aegisdrive`, so `AegisMotion` needs no import.)

- [ ] **Step 4: Device check.** Install. On Home, press-and-hold the "Launch AI Monitor" button → it should scale down ~4% and spring back on release, and still perform its action on tap. Spot-check one button on each of: Monitor (BEGIN MONITORING), Settings (a row/Log Out button), and — after logging out — Login (Access Portal). Confirm every button still triggers its normal action (press feedback must not swallow clicks).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/malik/aegisdrive/LoginActivity.kt app/src/main/java/com/malik/aegisdrive/SignUpActivity.kt app/src/main/java/com/malik/aegisdrive/ForgotPasswordActivity.kt app/src/main/java/com/malik/aegisdrive/SettingsActivity.kt app/src/main/java/com/malik/aegisdrive/EditProfileActivity.kt app/src/main/java/com/malik/aegisdrive/HomeFragment.kt app/src/main/java/com/malik/aegisdrive/ChatFragment.kt app/src/main/java/com/malik/aegisdrive/MonitorFragment.kt
git commit -m "feat(motion): tactile press feedback on buttons across all screens

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Full-app motion verification

**Files:** none (verification only; no code change unless a regression is found).

- [ ] **Step 1: Clean build.** `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew.bat clean assembleDebug --console=plain` → `BUILD SUCCESSFUL`. Install.
- [ ] **Step 2: Motion smoke test on device.** Walk every tab (Home → Navigate → Monitor → Chat) and confirm each transition cross-fades. Open Settings and Edit Profile from Home and confirm the slide+fade activity transition (forward) and reverse on back. Press a button on each screen and confirm the scale feedback + that the action still fires. Start and stop a Monitor session (confirm no camera regression and the return-to-Home from the prior fix still works). Screenshot the Monitor tab and one other screen.
- [ ] **Step 3: Report.** Summarize which transitions/feedback are now present per screen and any anomaly observed. No commit unless a regression fix was required.

---

## Self-Review (completed by plan author)

- **Spec coverage:** "modern animation + transition between each screen" for all 10 named screens →
  - Splash: bespoke reveal (exists) + activity transition to Main (exists) — no change needed, noted.
  - Login/Signup/Forgot: layout entrance (exists) + activity slide transitions (exists) + NEW press feedback (T4).
  - Home: fade-through + count-up + progress (exist) + NEW press feedback (T4).
  - Navigate: NEW fade-through transition (T2) + CSS entrance (exists).
  - Monitor: XML entrance (exists) + NEW enter fade-through (T3) + NEW press feedback (T4).
  - Chat: fade-through + bubble entrance (exist) + NEW press feedback (T4).
  - Settings/Edit Profile: layout entrance + activity transitions (exist) + NEW press feedback (T4).
  Every screen ends with a modern entrance, a screen transition, and (where it has buttons) press feedback. ✓
- **Placeholder scan:** all code shown verbatim; insertion points are concrete (after `setContentView`, last statement of `onViewCreated`, specific line anchors). No vague directives. ✓
- **Type/name consistency:** `applyPressToButtons(root: View?)` defined in T1, called with `findViewById(android.R.id.content)` (activities) and `view` (fragments) in T4 — signatures match. `MaterialFadeThrough` usage mirrors the proven `HomeFragment.kt:41-42` pattern. ✓
- **Restraint check:** no screen that already has an entrance/transition is re-animated; activity transitions (already global) are untouched; Monitor exit is deliberately omitted to protect the camera. No over-animation. ✓
