# Monitor Redesign + App-wide Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. During implementation, apply the `frontend-design` and `ui-styling` skills for visual decisions.

**Goal:** Redesign the Monitor tab into a modern "Control Deck" camera HUD (XML-only), and audit + polish the other 8 screens so the whole app is one cohesive Premium Minimal system.

**Architecture:** Android Material 3 **XML View system**, no Compose, no ViewBinding (`findViewById`). The Monitor is a live-camera HUD whose runtime colors/text/visibility are written by `MonitorFragment.kt` — that file is NOT touched; only `fragment_monitor.xml` + new resources change. Other screens get token/motion/responsiveness/wiring fixes only where they fail an audit checklist.

**Tech Stack:** Kotlin, Material Components 1.11.0, ConstraintLayout, CameraX, MediaPipe, TFLite (unchanged), Gradle (`gradlew.bat`), adb to a physical TECNO KJ7.

## Global Constraints

- **Do NOT open or edit `app/src/main/java/com/malik/aegisdrive/MonitorFragment.kt`.** Monitor work is `fragment_monitor.xml` + new resources ONLY.
- **Monitor root must stay `androidx.constraintlayout.widget.ConstraintLayout`; `@id/cameraPreview` must stay a direct child** (Kotlin injects `FaceOverlayView` into `cameraPreview.parent` using `ConstraintLayout.LayoutParams`).
- **These 20 view IDs are frozen — keep the exact ID AND view type:** `cameraPreview` (PreviewView), `tvTimer` `tvFPS` `tvLiveEar` `tvLiveMar` `tvStatusOverlay` `tvDetectionIcon` `tvDetectionLabel` `tvConfidence` `tvEyeState` `tvDrowsiness` `tvAlertCount` `tvCriticalWarning` `tvLiveText` `tvSafetyScore` (TextView), `pbSafetyScore` (ProgressBar), `btnStopMonitor` `btnMuteAlarm` (MaterialButton), `statusOverlay` `liveBadge` `warningBanner` (MaterialCardView).
- Container IDs `headerLayout` `topLeftPanel` `bottomDashboard` `safetyScoreCard` `guidelineWarning` `liveDot` are NOT bound in Kotlin — free to regroup/rename/replace.
- Runtime writes are behavior, not styling — XML defines only the resting state. Do not remove/rename bound IDs and do not hardcode a resting color that fights the detector.
- **`hud_*` colors are theme-independent — add any new HUD color ONLY to `app/src/main/res/values/colors.xml`** (never to `values-night/`).
- Reference tokens/styles, never inline hex/size/font — EXCEPT inside the Monitor HUD, where fixed hex is the established convention (theme-independent over the camera).
- Do not alter: the shield-eye logo, the dark palette values, or Firebase/Groq/CameraX/TFLite/MediaPipe behavior.
- Every shell that builds MUST first set: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
- Commit after each task. End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## Verification model (read once)

There are no unit tests for Android XML layouts. Each task's "test" is:
1. **Build:** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain` → must end **`BUILD SUCCESSFUL`** (exit 0).
2. **Device check** (Monitor + any changed screen): install and screenshot.
   - `& "C:\Users\L13\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`
   - `& "$adb" shell am start -n com.malik.aegisdrive/.SplashActivity`
   - Screenshot: `& "$adb" shell screencap -p /sdcard/x.png; & "$adb" pull /sdcard/x.png <scratch>\x.png`
   - Non-exported screens are reached by tapping: `& "$adb" shell input tap <x> <y>`.
3. **No-crash sanity:** app launches and Monitor tab opens without `bindViews` throwing (all 20 IDs resolve).

## Shared: Audit Procedure (used by Tasks 3–7)

Run these EXACT checks against each screen's file(s). Fix ONLY a check that fails; do not restyle a passing file.

- **Check A — Tokens.** List inline colors:
  `git grep -nE "#[0-9A-Fa-f]{6,8}" -- <layout.xml>`
  A hit is a gap UNLESS it is inside a HUD/theme-independent context. Fix: replace with the matching token from the map below.
  Color map: window bg → `@color/bg_deepest`; card bg → `@color/bg_surface`; elevated → `@color/bg_elevated`; hairline → `@color/border_subtle`; primary text → `@color/text_primary`; secondary text → `@color/text_secondary`; accent → `@color/aegis_blue`; safe/warn/danger → `@color/status_safe|status_warning|status_danger`.
- **Check B — Motion.** Confirm an entrance animation exists:
  `git grep -nE "layoutAnimation" -- <layout.xml>` OR the screen's Kotlin uses a Material transition (`git grep -nE "MaterialFadeThrough|MaterialSharedAxis|enterTransition" -- <Screen>.kt`).
  Fix if BOTH absent: add `android:layoutAnimation="@anim/layout_animation_stagger"` to the root vertical container (the direct parent of the stacked cards, inside any ScrollView).
- **Check C — Responsiveness.** Content-heavy screens must scroll and must not hardcode wide fixed widths:
  `git grep -nE "layout_width=\"[0-9]{3,}dp\"" -- <layout.xml>` (should be empty) and confirm a `ScrollView`/`NestedScrollView` wraps long content.
  Fix: change a `NNNdp` width to `0dp` + constraint or `match_parent`; wrap overflowing content in a `ScrollView`.
- **Check D — Wiring.** Every `findViewById` in the Kotlin must resolve to an ID in the layout:
  `git grep -oE "R\.id\.[A-Za-z0-9_]+" -- <Screen>.kt | sort -u` then confirm each appears as `@+id/` in the layout: `git grep -nE "@\+id/(<name>)" -- <layout.xml>`.
  Fix: restore the missing/renamed `@+id/` in the layout so it matches the Kotlin (never rename the Kotlin).

Deliverable of each audit task: a short findings note (which checks passed/failed per file) + the fixes for failures only.

---

### Task 1: New Monitor HUD resources

**Files:**
- Modify: `app/src/main/res/values/colors.xml` (add one HUD color)
- Create: `app/src/main/res/drawable/bg_bottom_glossy.xml`

**Interfaces:**
- Produces: `@color/hud_surface_strong` (denser deck glass), `@drawable/bg_bottom_glossy` (bottom camera scrim). Consumed by Task 2.

- [ ] **Step 1: Add the HUD deck color.** In `app/src/main/res/values/colors.xml`, inside the `<!-- === CAMERA HUD ... -->` block (after `hud_border`), add:

```xml
    <color name="hud_surface_strong">#F00A0F1A</color>
```

- [ ] **Step 2: Create the bottom scrim drawable.** Create `app/src/main/res/drawable/bg_bottom_glossy.xml` (mirror of `bg_top_glossy` but darkening the bottom — angle 90 puts `startColor` at the bottom):

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="90"
        android:startColor="#E60A0F1A"
        android:endColor="#000A0F1A" />
</shape>
```

- [ ] **Step 3: Build to validate resources.**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/drawable/bg_bottom_glossy.xml
git commit -m "feat(monitor): add hud_surface_strong + bottom scrim drawable

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Monitor "Control Deck" layout

**Files:**
- Modify (full replace): `app/src/main/res/layout/fragment_monitor.xml`

**Interfaces:**
- Consumes: `@color/hud_surface_strong`, `@drawable/bg_bottom_glossy` (Task 1); existing `@color/hud_surface|hud_text|hud_text_dim|hud_border`, `@color/aegis_blue`, `@drawable/bg_top_glossy`, `@drawable/progress_rounded`, `@drawable/ic_warning_modern`, `@anim/layout_animation_stagger`, and all `@string/*` + `@dimen/*` referenced below.
- Produces: nothing for later tasks; MUST preserve all 20 frozen IDs so `MonitorFragment.bindViews()` resolves.

- [ ] **Step 1: Replace `fragment_monitor.xml` with the Control Deck layout.** Write the file exactly as below.

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/windowBackground"
    android:animateLayoutChanges="true"
    android:fitsSystemWindows="true">

    <!-- CAMERA (frozen: direct child, hosts FaceOverlayView) -->
    <androidx.camera.view.PreviewView
        android:id="@+id/cameraPreview"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:scaleType="fillCenter" />

    <!-- Top scrim for header legibility -->
    <View
        android:layout_width="match_parent"
        android:layout_height="200dp"
        android:background="@drawable/bg_top_glossy"
        app:layout_constraintTop_toTopOf="parent" />

    <!-- Bottom scrim for control-deck legibility -->
    <View
        android:layout_width="match_parent"
        android:layout_height="360dp"
        android:background="@drawable/bg_bottom_glossy"
        app:layout_constraintBottom_toBottomOf="parent" />

    <!-- ================= HEADER ================= -->
    <LinearLayout
        android:id="@+id/headerLayout"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="top"
        android:paddingHorizontal="@dimen/space_20"
        android:paddingTop="@dimen/space_16"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent">

        <LinearLayout
            android:id="@+id/topLeftPanel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/aegis_drive"
                android:textColor="@color/hud_text"
                android:textSize="20sp"
                android:fontFamily="@font/space_grotesk_bold"
                android:letterSpacing="0.02" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/space_2"
                android:text="@string/sensory_intelligence"
                android:textColor="@color/aegis_blue"
                android:textAppearance="@style/TextAppearance.Aegis.Label" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="end">

            <com.google.android.material.card.MaterialCardView
                android:id="@+id/statusOverlay"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="@color/hud_surface"
                app:cardCornerRadius="@dimen/radius_pill"
                app:cardElevation="0dp"
                app:strokeColor="@color/hud_border"
                app:strokeWidth="@dimen/stroke_hairline">
                <TextView
                    android:id="@+id/tvStatusOverlay"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/standby"
                    android:textColor="@color/hud_text_dim"
                    android:textAppearance="@style/TextAppearance.Aegis.Label"
                    android:paddingHorizontal="@dimen/space_12"
                    android:paddingVertical="@dimen/space_4" />
            </com.google.android.material.card.MaterialCardView>

            <com.google.android.material.card.MaterialCardView
                android:id="@+id/liveBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/space_6"
                app:cardBackgroundColor="#AAEF4444"
                app:cardCornerRadius="@dimen/radius_pill"
                app:cardElevation="0dp">
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:paddingHorizontal="@dimen/space_10"
                    android:paddingVertical="@dimen/space_4">
                    <View
                        android:id="@+id/liveDot"
                        android:layout_width="6dp"
                        android:layout_height="6dp"
                        android:background="@android:color/white"
                        android:layout_marginEnd="@dimen/space_6" />
                    <TextView
                        android:id="@+id/tvLiveText"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/offline"
                        android:textColor="#FFFFFF"
                        android:textAppearance="@style/TextAppearance.Aegis.Label" />
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
        </LinearLayout>
    </LinearLayout>

    <!-- ============ TELEMETRY STRIP ============ -->
    <LinearLayout
        android:id="@+id/telemetryStrip"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:layout_marginTop="@dimen/space_10"
        android:paddingHorizontal="@dimen/space_20"
        app:layout_constraintTop_toBottomOf="@id/headerLayout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <TextView
            android:id="@+id/tvLiveEar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/eye_openness_default"
            android:textColor="@color/hud_text_dim"
            android:textSize="10sp"
            android:fontFamily="monospace" />
        <View
            android:layout_width="1dp"
            android:layout_height="10dp"
            android:background="@color/hud_border"
            android:layout_marginHorizontal="@dimen/space_8"
            android:layout_gravity="center_vertical" />
        <TextView
            android:id="@+id/tvLiveMar"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="@string/mouth_gap_default"
            android:textColor="@color/hud_text_dim"
            android:textSize="10sp"
            android:fontFamily="monospace" />
        <TextView
            android:id="@+id/tvTimer"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/default_timer"
            android:textColor="@color/hud_text"
            android:textSize="12sp"
            android:fontFamily="monospace" />
        <View
            android:layout_width="1dp"
            android:layout_height="10dp"
            android:background="@color/hud_border"
            android:layout_marginHorizontal="@dimen/space_8"
            android:layout_gravity="center_vertical" />
        <TextView
            android:id="@+id/tvFPS"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/default_fps"
            android:textColor="@color/hud_text_dim"
            android:textSize="10sp"
            android:fontFamily="monospace" />
    </LinearLayout>

    <!-- Guideline for the floating danger banner -->
    <androidx.constraintlayout.widget.Guideline
        android:id="@+id/guidelineWarning"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        app:layout_constraintGuide_percent="0.18" />

    <!-- ============ DANGER BANNER (frozen id/type; auto-animates via animateLayoutChanges) ============ -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/warningBanner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:cardBackgroundColor="#F0EF4444"
        app:cardCornerRadius="@dimen/radius_pill"
        app:cardElevation="@dimen/elevation_float"
        app:layout_constraintTop_toTopOf="@id/guidelineWarning"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingHorizontal="@dimen/space_20"
            android:paddingVertical="@dimen/space_10">
            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@drawable/ic_warning_modern"
                app:tint="#FFFFFF"
                android:layout_marginEnd="@dimen/space_10" />
            <TextView
                android:id="@+id/tvCriticalWarning"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/danger_eyes_closed"
                android:textColor="#FFFFFF"
                android:fontFamily="@font/inter_semibold"
                android:textSize="14sp"
                android:letterSpacing="0.02" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- ============ MUTE (frozen id/type) ============ -->
    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnMuteAlarm"
        android:layout_width="wrap_content"
        android:layout_height="44dp"
        android:layout_marginEnd="@dimen/space_20"
        android:layout_marginBottom="@dimen/space_12"
        android:text="@string/mute_alarm"
        android:textColor="#FFFFFF"
        android:textSize="11sp"
        android:fontFamily="@font/inter_semibold"
        android:textAllCaps="false"
        android:visibility="gone"
        app:backgroundTint="#EF4444"
        app:cornerRadius="@dimen/radius_pill"
        app:elevation="@dimen/elevation_float"
        app:layout_constraintBottom_toTopOf="@+id/controlDeck"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- ============ CONTROL DECK ============ -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/controlDeck"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="@dimen/space_20"
        android:layout_marginBottom="@dimen/space_12"
        app:cardBackgroundColor="@color/hud_surface_strong"
        app:cardCornerRadius="@dimen/radius_xl"
        app:cardElevation="0dp"
        app:strokeColor="@color/hud_border"
        app:strokeWidth="@dimen/stroke_hairline"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@+id/btnStopMonitor">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingHorizontal="@dimen/space_20"
            android:paddingVertical="@dimen/space_16">

            <!-- Hero: safety score -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:baselineAligned="false">
                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/telemetry_safety_index"
                    android:textColor="@color/hud_text_dim"
                    android:textAppearance="@style/TextAppearance.Aegis.Label" />
                <TextView
                    android:id="@+id/tvSafetyScore"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/full_score"
                    android:textColor="#34D399"
                    android:fontFamily="@font/space_grotesk_bold"
                    android:textSize="26sp" />
            </LinearLayout>

            <ProgressBar
                android:id="@+id/pbSafetyScore"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="8dp"
                android:layout_marginTop="@dimen/space_8"
                android:max="100"
                android:progress="100"
                android:progressDrawable="@drawable/progress_rounded"
                android:progressTint="#34D399" />

            <!-- Divider -->
            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginVertical="@dimen/space_16"
                android:background="@color/hud_border" />

            <!-- Metric row (staggered entrance) -->
            <LinearLayout
                android:id="@+id/metricRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:baselineAligned="false"
                android:layoutAnimation="@anim/layout_animation_stagger">

                <!-- AI Engine -->
                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical"
                    android:gravity="center">
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/ai_engine"
                        android:maxLines="1"
                        android:ellipsize="end"
                        android:textColor="@color/hud_text_dim"
                        android:textAppearance="@style/TextAppearance.Aegis.Label" />
                    <TextView
                        android:id="@+id/tvDetectionIcon"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="–"
                        android:textColor="@color/hud_text"
                        android:textSize="20sp"
                        android:layout_marginTop="@dimen/space_4"
                        tools:ignore="HardcodedText" />
                    <TextView
                        android:id="@+id/tvDetectionLabel"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/waiting"
                        android:maxLines="1"
                        android:ellipsize="end"
                        android:textColor="@color/hud_text"
                        android:fontFamily="@font/inter_semibold"
                        android:textSize="10sp" />
                </LinearLayout>

                <View
                    android:layout_width="1dp"
                    android:layout_height="match_parent"
                    android:layout_marginHorizontal="@dimen/space_8"
                    android:background="@color/hud_border" />

                <!-- Eye State -->
                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical"
                    android:gravity="center">
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/eye_state"
                        android:maxLines="1"
                        android:ellipsize="end"
                        android:textColor="@color/hud_text_dim"
                        android:textAppearance="@style/TextAppearance.Aegis.Label" />
                    <TextView
                        android:id="@+id/tvEyeState"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="–"
                        android:textColor="@color/hud_text"
                        android:fontFamily="@font/space_grotesk_semibold"
                        android:textSize="14sp"
                        android:layout_marginTop="@dimen/space_6"
                        tools:ignore="HardcodedText" />
                    <TextView
                        android:id="@+id/tvConfidence"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="–"
                        android:textColor="@color/aegis_blue"
                        android:textSize="10sp"
                        tools:ignore="HardcodedText" />
                </LinearLayout>

                <View
                    android:layout_width="1dp"
                    android:layout_height="match_parent"
                    android:layout_marginHorizontal="@dimen/space_8"
                    android:background="@color/hud_border" />

                <!-- Alerts -->
                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical"
                    android:gravity="center">
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/alerts"
                        android:maxLines="1"
                        android:ellipsize="end"
                        android:textColor="@color/hud_text_dim"
                        android:textAppearance="@style/TextAppearance.Aegis.Label" />
                    <TextView
                        android:id="@+id/tvAlertCount"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="0"
                        android:textColor="#F59E0B"
                        android:fontFamily="@font/space_grotesk_bold"
                        android:textSize="20sp"
                        android:layout_marginTop="@dimen/space_4"
                        tools:ignore="HardcodedText" />
                    <TextView
                        android:id="@+id/tvDrowsiness"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/stable"
                        android:maxLines="1"
                        android:ellipsize="end"
                        android:textColor="@color/hud_text_dim"
                        android:textSize="10sp" />
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <!-- ============ START / STOP (frozen id/type; Kotlin owns its colors) ============ -->
    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnStopMonitor"
        android:layout_width="0dp"
        android:layout_height="@dimen/button_height"
        android:insetTop="0dp"
        android:insetBottom="0dp"
        android:layout_marginHorizontal="@dimen/space_20"
        android:layout_marginBottom="@dimen/space_32"
        android:text="@string/begin_monitoring"
        android:textSize="15sp"
        android:fontFamily="@font/inter_semibold"
        android:textAllCaps="false"
        android:letterSpacing="0.02"
        android:textColor="?attr/colorOnPrimary"
        app:backgroundTint="?attr/colorPrimary"
        app:cornerRadius="@dimen/radius_md"
        app:elevation="0dp"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Build.**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. If AAPT errors on a missing resource, confirm Task 1 landed and the `@font/*`, `@string/*`, `@drawable/ic_warning_modern`, `@drawable/progress_rounded` names exist.

- [ ] **Step 3: Install + open Monitor + screenshot.**

```bash
$adb = "C:\Users\L13\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.malik.aegisdrive/.SplashActivity
```
Then tap the Monitor bottom-nav tab (3rd of 4 tabs — approximate `input tap` at the 3rd-quarter of the nav bar), and screencap/pull.
Expected: HUD renders — header brand + STANDBY/OFFLINE pills, telemetry strip, control deck with `100%` safety hero + full-width bar + 3 divided metrics, `BEGIN MONITORING` button. No crash (proves all 20 IDs resolved in `bindViews`).

- [ ] **Step 4: Functional sanity — Start/Stop.** Tap `BEGIN MONITORING`; grant camera if prompted. Expected: button becomes `STOP MONITORING` (cyan), LIVE pill turns green, status → SECURE, metrics begin updating. Tap `STOP MONITORING`; expected: it writes a session and returns to Home (existing Kotlin behavior, now over the new layout).

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/res/layout/fragment_monitor.xml
git commit -m "feat(monitor): Control Deck HUD redesign (XML-only, frozen IDs preserved)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Audit + polish — Splash & Auth (Splash, Login, Signup, Forgot Password)

**Files (inspect; edit only on failure):**
- `app/src/main/res/layout/activity_splash.xml` + `SplashActivity.kt`
- `app/src/main/res/layout/activity_login.xml` + `LoginActivity.kt`
- `app/src/main/res/layout/activity_signup.xml` + `SignUpActivity.kt`
- `app/src/main/res/layout/activity_forgot_password.xml` + `ForgotPasswordActivity.kt`

- [ ] **Step 1: Run the Shared Audit Procedure (Checks A–D)** against each file above. Record pass/fail per check.
- [ ] **Step 2: Apply fixes for failures only**, using the exact fixes defined in the Audit Procedure (token map for A; `layout_animation_stagger` for B; `0dp`/`ScrollView` for C; restore `@+id/` for D). Auth screens especially: verify Login/Signup/Forgot are reachable and that every field/button ID the Kotlin binds exists (Check D) — these were compiled but NOT screenshotted previously.
- [ ] **Step 3: Build.** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device check.** Log out (Settings → Log Out) to reach auth, then install/screenshot Login, Signup, Forgot, and confirm Splash on cold start. Expected: token-consistent, staggered entrance, no clipped fields on the device width.
- [ ] **Step 5: Commit** (only if files changed).

```bash
git add -A app/src/main/res/layout app/src/main/java
git commit -m "polish(auth): audit fixes for splash/login/signup/forgot

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Audit + polish — Home dashboard

**Files (inspect; edit only on failure):**
- `app/src/main/res/layout/fragment_home.xml` + `HomeFragment.kt`

- [ ] **Step 1: Run the Shared Audit Procedure (Checks A–D)** against the two files. Record pass/fail.
- [ ] **Step 2: Apply fixes for failures only** using the Audit Procedure's exact fixes. Home already has count-up/progress + `MaterialFadeThrough` per the handoff doc — do NOT duplicate; only fix a check that actually fails.
- [ ] **Step 3: Build.** `...gradlew.bat assembleDebug --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device check.** Open Home; screenshot. Expected: hero score card, metric grid, cards render token-consistent; entrance animation plays; scrolls without clipping.
- [ ] **Step 5: Commit** (only if changed).

```bash
git add -A app/src/main/res/layout/fragment_home.xml app/src/main/java/com/malik/aegisdrive/HomeFragment.kt
git commit -m "polish(home): audit fixes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Audit + polish — AI Chat

**Files (inspect; edit only on failure):**
- `app/src/main/res/layout/fragment_chat.xml` + `ChatFragment.kt`

- [ ] **Step 1: Run the Shared Audit Procedure (Checks A–D).** For Chat, bubbles are built programmatically in Kotlin — for Check A, confirm bubble colors use `ContextCompat.getColor(R.color.*)` (token-driven) not raw `Color.parseColor("#...")`; for Check D, confirm the input field / send button / RecyclerView IDs bound in `ChatFragment.kt` exist in the layout.
- [ ] **Step 2: Apply fixes for failures only.**
- [ ] **Step 3: Build.** → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device check.** Open AI Chat; send one message; screenshot. Expected: pill input, icon chips, theme-aware bubbles with entrance animation; message sends (Groq path unchanged).
- [ ] **Step 5: Commit** (only if changed).

```bash
git add -A app/src/main/res/layout/fragment_chat.xml app/src/main/java/com/malik/aegisdrive/ChatFragment.kt
git commit -m "polish(chat): audit fixes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Audit + polish — Settings & Edit Profile

**Files (inspect; edit only on failure):**
- `app/src/main/res/layout/activity_settings.xml` + `SettingsActivity.kt`
- `app/src/main/res/layout/activity_edit_profile.xml` + `EditProfileActivity.kt`

- [ ] **Step 1: Run the Shared Audit Procedure (Checks A–D)** against both screens. Note: Settings uses `MaterialSwitch` and `focusableInTouchMode` on the content root per the handoff — preserve both.
- [ ] **Step 2: Apply fixes for failures only.**
- [ ] **Step 3: Build.** → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device check.** Open Settings (Home → gear), toggle each switch, open Edit Profile; screenshot both. Expected: token-consistent rows, switches toggle, no auto-scroll-to-focus jump, danger buttons render; Edit Profile fields not clipped.
- [ ] **Step 5: Commit** (only if changed).

```bash
git add -A app/src/main/res/layout/activity_settings.xml app/src/main/res/layout/activity_edit_profile.xml app/src/main/java
git commit -m "polish(settings): audit fixes for settings + edit profile

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Audit + polish — Navigate (WebView map)

**Files (inspect; edit only on failure):**
- `app/src/main/assets/navigate/style.css` (+ `assets/navigate/index.html`, `app.js` — DO NOT change JS-referenced control markup / MapLibre logic)

- [ ] **Step 1: Audit the CSS.** Check A (tokens): confirm `style.css` uses the aligned CSS custom properties / `@font-face` Inter+Space Grotesk (not stray hex that drifts from the app palette). Check B (motion): confirm entrance keyframes exist. Check C (responsiveness): confirm layout uses relative units and the map fills the viewport on the device width. Wiring: do NOT touch `index.html`/`app.js` (emoji glyph controls are JS-referenced).
- [ ] **Step 2: Apply CSS-only fixes for failures.** Match colors to the design tokens (dark-glass values consistent with `hud_*`/`bg_surface`); keep MapLibre + JS bridge untouched.
- [ ] **Step 3: Build.** → `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device check.** Open Navigate; screenshot. Expected: dark-glass premium map chrome, fonts loaded, controls still functional (search + start-drive), no layout overflow.
- [ ] **Step 5: Commit** (only if changed).

```bash
git add -A app/src/main/assets/navigate/style.css
git commit -m "polish(navigate): CSS token/motion/responsive audit fixes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Full-app verification & handoff update

**Files:**
- Modify: `md/UI_UX_REDESIGN_SESSION.md` (record the Monitor redesign + audit outcomes)

- [ ] **Step 1: Clean build.** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat clean assembleDebug --console=plain` → `BUILD SUCCESSFUL`.
- [ ] **Step 2: Install & smoke-test the full flow on the TECNO KJ7:** launch → Home → Navigate → **Monitor (start monitoring ~10s, confirm metrics + safety bar move, then stop → session writes and returns Home)** → AI Chat (send a message) → Settings (toggle a switch) → Edit Profile. Capture a screenshot of the redesigned Monitor (idle) and any screen changed in Tasks 3–7.
- [ ] **Step 3: Update the handoff doc.** In `md/UI_UX_REDESIGN_SESSION.md`, update the **Monitor** row of §4 to describe the Control Deck redesign (single glass deck, safety hero, divided metric row, telemetry strip, bottom scrim, `animateLayoutChanges` + `layoutAnimation`, `hud_surface_strong`/`bg_bottom_glossy` added) and note which §6 items were resolved by the audit.
- [ ] **Step 4: Commit.**

```bash
git add md/UI_UX_REDESIGN_SESSION.md
git commit -m "docs: record Monitor Control Deck redesign + audit results

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (completed by plan author)

- **Spec coverage:** Monitor full redesign → Tasks 1–2. Audit+polish of all 8 screens (Splash/Login/Signup/Forgot → T3; Home → T4; Chat → T5; Settings/Edit Profile → T6; Navigate → T7). Motion (entrance stagger + `animateLayoutChanges`) → T2 + Audit Check B. Responsiveness → Audit Check C + Monitor constraint/percent. Wiring → Audit Check D + frozen-ID preservation. Verification/build/device → every task + T8. Out-of-scope items honored via Global Constraints. ✓
- **Placeholder scan:** Monitor XML is provided in full; audit fixes reference concrete commands + a concrete token map, not vague "handle edge cases". ✓
- **Type/name consistency:** frozen IDs used verbatim from `bindViews()`; new decorative IDs (`telemetryStrip`, `controlDeck`, `metricRow`) do not collide with bound IDs; `hud_surface_strong` + `bg_bottom_glossy` defined in T1 and consumed in T2. ✓
