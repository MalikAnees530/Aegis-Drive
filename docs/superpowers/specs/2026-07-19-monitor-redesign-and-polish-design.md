# AegisDrive — Monitor Redesign + App-wide Polish · Design Spec

**Date:** 2026-07-19 · **Package:** `com.malik.aegisdrive` · **Branch:** `redesign/premium-minimal-ui`

## Goal

Fully redesign the **Monitor tab** UI/UX (modern instrument-cluster HUD over the live
camera), and run an **audit + targeted polish** pass over the other 8 user-facing screens so the
whole app reads as one cohesive, professional "Premium Minimal" system. Dark theme unchanged.

## Locked decisions (from brainstorming)

1. **Monitor scope:** full ground-up *visual* redesign, delivered through **`fragment_monitor.xml`
   only**. `MonitorFragment.kt` is **not opened** (pure-XML restyle).
2. **Other screens:** audit + targeted polish only — edit a file *only* where it fails the checklist.
   No churn on already-verified UI.
3. **HUD composition:** **Control Deck** — unified top status bar + thin telemetry strip; a single
   connected bottom glass "control deck" with the safety score as hero, the 3 metrics as an
   internally-divided row, and START/STOP spanning the base; DANGER banner floats center.
4. **Motion budget on Monitor:** entrance stagger + auto layout-change transitions + Material
   ripple only. **No looping / count-up / data-driven motion** (that needs Kotlin, which stays closed).

## Hard constraints (must honor)

- **Root stays `ConstraintLayout`; `cameraPreview` stays a direct child** — Kotlin injects
  `FaceOverlayView` into `cameraPreview.parent` using `ConstraintLayout.LayoutParams`
  (`MonitorFragment.kt` ~L149-166).
- **20 bound view IDs are frozen** (ID **and** view type). Bound in `bindViews()`:
  `cameraPreview`, `tvTimer`, `tvFPS`, `tvLiveEar`, `tvLiveMar`, `tvStatusOverlay`,
  `tvDetectionIcon`, `tvDetectionLabel`, `tvConfidence`, `tvEyeState`, `tvDrowsiness`,
  `tvAlertCount`, `btnStopMonitor`, `btnMuteAlarm`, `statusOverlay`, `liveBadge`, `tvLiveText`,
  `tvSafetyScore`, `pbSafetyScore`, `warningBanner`.
- **Runtime values are behavior, not styling** — Kotlin writes text/colors/visibility into these
  views (`tvEyeState` → red on closed eyes, `pbSafetyScore` green→amber→red retint,
  `btnStopMonitor` bg `#38BDF8` / text `#0F172A` in both states, `warningBanner`/`btnMuteAlarm`
  visibility toggles). XML only defines the resting state; do not fight the runtime.
- **Container IDs are FREE** (not bound in Kotlin, XML-only): `headerLayout`, `topLeftPanel`,
  `bottomDashboard`, `safetyScoreCard`, `guidelineWarning`, `liveDot` — may be regrouped,
  renamed, replaced.
- **`hud_*` colors are theme-independent** — defined ONLY in `values/colors.xml` (dark glass +
  fixed light text in both app modes). Any new HUD color follows the same rule.
- Preserve the shield-eye logo; do not alter Firebase/Groq/CameraX/TFLite/MediaPipe behavior or
  the dark palette.

## Monitor layout (top → bottom)

1. **Camera** — `cameraPreview` full-bleed, unchanged.
2. **Scrims** — keep existing top glossy gradient; **add bottom scrim** (`bg_bottom_glossy`) for
   deck legibility over any frame.
3. **Header** — brand left (`AEGIS DRIVE` Space Grotesk bold + `sensory intelligence` in
   `aegis_blue`); right vertical pill stack: `statusOverlay` (`tvStatusOverlay`) above `liveBadge`
   (`liveDot` + `tvLiveText`). Refined glass + hairline.
4. **Telemetry strip** — one thin monospace readout: `tvLiveEar · tvLiveMar` (left),
   `tvTimer · tvFPS` (right). Monospace kept (Kotlin writes formatted strings here).
5. **DANGER banner** — `warningBanner` (MaterialCardView) restyled as centered danger-glass pill
   (`ic_warning_modern` + `tvCriticalWarning`) at ~18% guideline. Default `visibility=gone`.
6. **Mute** — `btnMuteAlarm` (MaterialButton) danger pill floating above the deck, right-aligned.
   Default `visibility=gone`.
7. **Control Deck** — single glass `MaterialCardView` (flat + hairline, `hud_*`):
   - Hero: `SAFETY INDEX` label + `tvSafetyScore` large Space Grotesk number; `pbSafetyScore`
     full-width `progress_rounded` bar beneath.
   - hairline divider.
   - Metric row: 3 columns split by vertical hairlines — **AI Engine** (`tvDetectionIcon` emoji +
     `tvDetectionLabel`), **Eye State** (`tvEyeState` + `tvConfidence`), **Alerts** (`tvAlertCount`
     + `tvDrowsiness`). `layoutAnimation` staggered entrance.
8. **Start/Stop** — `btnStopMonitor` full-width base; XML = resting look only (Kotlin owns color).

## Motion (pure-XML, auto-running only)

- `android:animateLayoutChanges="true"` on root → auto fade/slide for `warningBanner` +
  `btnMuteAlarm` when Kotlin toggles visibility.
- `android:layoutAnimation="@anim/layout_animation_stagger"` on the deck's metric row (and deck
  container) → staggered entrance on first layout.
- Automatic Material ripple on `btnStopMonitor` / `btnMuteAlarm`.

## New resources

- **Colors** (`values/colors.xml` only, theme-independent): add as needed, e.g.
  `hud_surface_strong`, `hud_scrim`.
- **Drawable:** `bg_bottom_glossy` (bottom scrim gradient). Reuse `progress_rounded`,
  `ic_warning_modern`, `bg_top_glossy`.
- **Anim:** reuse existing `layout_animation_stagger`, `item_slide_up`.

## Responsiveness

`0dp` + `app:layout_constraintWidth_percent` widths; `dimens` spacing/text tokens; `wrap_content`
heights; metric labels single-line + `ellipsize`; `fitsSystemWindows` for notch/insets. Holds from
small phones upward.

## Other 8 screens — audit checklist (edit only on failure)

Screens: **Splash, Login, Signup, Forgot Password, Home, Navigate (CSS), AI Chat, Settings,
Edit Profile.** For each, verify and fix *only* gaps:

- **Tokens:** no stray inline hex/size/font — all via `@color` / `@dimen` / `@style` / `@font`.
- **Motion:** entrance animation present (`layoutAnimation` or `MaterialFadeThrough`); transitions wired.
- **Responsiveness:** scrollable where needed; constraint/percent (no clipping fixed widths); insets handled.
- **Wiring:** every XML `@+id` matches its Kotlin `findViewById` (no runtime crash).

Deliverable: a per-screen findings list; changes confined to real gaps.

## Verification

- `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr`, then
  `.\gradlew.bat assembleDebug --console=plain` → must be **exit 0**.
- Install to **TECNO KJ7** (`adb install -r`), screenshot Monitor idle (active if reachable; HUD is
  theme-independent so light/dark are identical) + any polished screen changed.
- Sanity: app launches; `bindViews` resolves all 20 IDs (no crash); Start/Stop toggles.

## Out of scope

Kotlin logic, shield-eye logo, Firebase/Groq/CameraX/TFLite/MediaPipe behavior, dark palette,
and any looping/data-driven animation on Monitor.
