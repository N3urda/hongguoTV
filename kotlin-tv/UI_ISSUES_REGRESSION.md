# Issue #4 / #6 / #8 regression checklist

This change addresses controls only, not a visual redesign. Device checks below are
release gates, not claims that a physical TV has passed them.

## Changes

- #4: Settings and the playback settings menu offer 0°, 90°, 180°, and 270°.
  At 0° playback retains the original SurfaceView. Nonzero rotations use a
  TextureView; crossing the 0° boundary rebuilds once with the current episode,
  position, and play/pause intent preserved. Changing between nonzero angles does
  not recreate the player. A TextureView-backed video canvas swaps width/height for quarter turns before
  rotating around its centre. HUD/menu coordinates remain in screen space.
  FIT, ZOOM, and FILL apply in the rotated canvas. Rotation is saved and backed up.
- #6: The focused series' header scrolls the complete title. A card's long-press
  or Menu action includes “查看完整标题”, showing the untruncated title as dialog
  text (scrollable for long text). Closing restores the card focus.
- #8: “常驻播放进度” defaults off, preserving the existing quiet playback experience.
  When on, a compact time/progress strip remains after the transient HUD hides.
  It does not take focus or intercept Back. Showing controls hides the duplicate
  strip; dismissing controls restores it. The preference is saved and backed up.
  Older backups default to no rotation and no persistent strip.

## Automated coverage

Run from the repository root:

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
```

JVM tests cover quarter-turn canvas dimensions, landscape/portrait and invalid
inputs, visibility combinations, unknown/negative/overflow-safe progress,
surface selection and rebuild transitions, preferences decoded from disk, backward-compatible backup imports, invalid
backup values, and round-trip export of the new preferences. They do not prove
video rendering, remote navigation, or hardware decoder performance.

## Device / emulator checks required

1. Search “谁说没有灵根就不能修仙之无灵证道”; move between seasons with different
   suffixes. Wait for the full focused title to scroll, then open “查看完整标题”
   using Menu and long-press. Check the full suffix and restored focus after Back,
   Cancel, and confirmation. Repeat at 720p and with enlarged font settings.
2. Play portrait and landscape video. At every rotation check FIT (all video
   visible), ZOOM (expected crop), FILL (expected stretch), centred geometry,
   metadata-rotated media, and stable screen-space HUD. Change rotation repeatedly,
   switch episode, seek, background/foreground, and restart the app. Check 0° →
   90° → 270° → 0° both paused and playing, including during initial preparation;
   the episode/position/play intent must survive surface-type changes.
3. Turn persistent progress on/off in both settings locations. Wait beyond 4.5s,
   pause/resume/seek, switch episodes, simulate buffering/error, and background the
   app. Ensure elapsed time advances, errors do not leave a stale strip, and Back
   exits when only the strip is visible. With a full HUD visible, first Back hides
   the HUD and second Back exits as before.
4. Export/import settings, import a legacy backup without the new fields, and
   restart to verify persistence. With “restore settings” off, keep local choices.
5. Settings already live inside the weighted ScrollView in showCatalog(tab=5),
   so six preference rows remain scrollable; no second nested scroll was added.
   Check remote navigation across unequal settings columns and all newly added
   controls. Test TextureView on a low-end 32-bit physical TV for dropped frames
   and memory/performance; an emulator is not a substitute for this gate.
