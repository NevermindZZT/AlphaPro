# Remote control design — R3 layout / R2 interaction

## Product interaction

The user clarified on 2026-10-28 that product UI must not contain development-test
badges, duplicate per-card mode titles, or a prerequisite checkbox. Keep concise
camera setup instructions, not extra normal-use confirmations.

- Single uses the established large click shutter.
- Burst uses the same160dp visual language with press-and-hold: touch-down owns a
  gesture, finger-up/cancel/drag-off requests release. There is no duration setting.
- BULB offers Hold and Timer. Touch-down sends the complete Bluetooth start press;
  finger-up sends the end press after a clean accepted start. Timer uses the selected
  planned duration. Neither uses an operator-start confirmation gate. Known camera
  inactive feedback suppresses an end press that could otherwise restart exposure.
- Timed BULB duration and interval/count use one AlphaParameterSlider component with
  a settings icon and validated exact-value dialog. Seconds use logarithmic scaling
  for fine short-duration control; count uses linear scaling. No persistent input box.
- The only normal mode title is the mode selector. Abnormal transport/release results
  still require a camera check: that is a real safety error, not a development badge.

Manual press gestures carry unique tokens. Recomposition does not restart pointer
handling; a stale up/disposal cannot release a successor. Accessibility uses explicit
start/stop semantics; keyboard key-up and focus loss release the owned token. These
input paths still require Android device verification. An internal lost-input timeout
is protection against a missing gesture end, not a user-configured burst duration.

## R3 layout refinement (2026-10-28)

The user's phone screenshot showed fragmented instructions/actions and sparse content
packed at the top. This revision changes only RemoteShutterScreen and bilingual
product copy; no ViewModel, GATT, controller or shared shutter behavior is changed.

- Keep one mode selector outside the scrolling body. Bound selector/body to560dp and
  center horizontally on wider windows instead of stretching an empty control panel.
- One content-sized capture card contains setup instructions, any mode parameters,
  the160dp shutter, runtime feedback, and compact AF/movie controls separated by a
  subtle divider. Do not repeat the mode title or add decorative placeholders.
- Single/Burst receive a bounded16–80dp viewport-based resting top inset. Short
  windows keep minimal spacing; parameter-heavy modes use16dp and scroll naturally.
  This is modest rebalancing, not a fill-height card or a larger shutter.
- Include Scaffold bottom padding when calculating that inset so appearance of the
  pinned Stop bar does not move a held target. Runtime feedback is BELOW the shutter;
  inserting the first timer must not displace the button under the user's finger.
  Keep a single key(mode, manual) subtree, not separate idle/active shutters.
- Use a lower-emphasis information card for foreground/cleanup guidance, split into
  readable paragraphs without deleting uncertain-result or saved-photo caveats.
  Actual errors/uncertain releases use errorContainer and stay expanded. On a short
  window the error card may require scrolling; Stop/acknowledgment remains pinned.
- The circle uses a short localized stopping label; full release-request status stays
  in the status area/pinned action. Compact auxiliary actions retain Material touch
  targets and wrapping; shared visual size tokens remain unchanged.

## R4 layout & unified shutter simplification (2026-10-29)

Following user analysis that camera drive mode (Single vs Cont. Shooting) controls
burst behavior on physical Sony bodies and App shutter/AF/movie controls are otherwise
identical, the 4-mode segmented row is simplified into two clear functional domains:

- **Standard Remote (标准遥控)**: Default mode for immediate shooting.
  - Unified 160dp shutter button (`AlphaHoldShutterButton` with `captureUnified`):
    - Pure centered camera icon (64dp); internal text removed so the icon is never squeezed off-center.
    - Status/stopping text renders below the button without shifting button geometry.
    - Tap (< 300ms): sends 07, waits 150ms, sends 09, holds 09 for 150ms min full press,
      releases 08/50ms/06. Matches verified single sequence byte-for-byte and millisecond-for-millisecond.
    - Hold (>= 300ms): sends 07/150ms/09, holds full-down until finger-up, releases 08/50ms/06.
    - TalkBack / accessibility click triggers single photo via `onTap`.
  - Prominent AF lock (AF-ON toggle) and movie recording (REC toggle) auxiliary row:
    enlarged to `AlphaButtonSize.Large` (48dp min height, 20dp icon, 50% row weight) for easy one-handed thumb reach.
  - No parameter sliders or empty space; viewport resting inset centers cleanly.
- **Timed Shooting (定时拍摄)**: Automated tasks requiring app-scheduled timing.
  - 160dp shutter uses centered icon-only display; action label/clock sits cleanly below.
  - Sub-selector: BULB Exposure (B 门长曝光) vs Interval (间隔拍摄).
  - BULB: exposure duration slider (1..3600s log scale, exact dialog); start/stop timer.
  - Interval: shooting interval (1..3600s log scale) and count (1..999 linear); start/stop.
  - Full-screen monospace clock, remaining time / trigger progress, and next trigger countdown.
  - Foreground guidance and error notice cards retained.
- All Bluetooth transport guarantees (session pinning, non-cancellable release cleanup,
  pinned bottom stop bar, back-handler protection, keep-screen-on) remain fully preserved.

Source review/build success is not Android screenshot or gesture validation. Check
320/360/412dp, short landscape and wide windows; Chinese/English, light/dark theme,
large fonts, and idle/running/stopping/error states. In particular verify shutter
position across the first status update and appearance/disappearance of pinned Stop.
No emulator/device screenshot was captured in this session; adb could not initialize
its default home directory. A pre-existing presentation limit remains: no persistent
explicit completion label exists for a successful sub-second manual hold.

## Protocol facts and limits

The user previously verified single07/150ms/09/150ms/08/50ms/06, AF down07/up06,
and movie0f/150ms/0e. Their successful sequence remains the regression baseline.

[α6700 continuous drive](https://helpguide.sony.net/ilc/2320/v1/en/contents/0407C_drivemode_continuous.html)
shoots continuously while held; set body Drive Mode → Cont. Shooting. App frame
counts/FPS are not guaranteed. [RMT-P1BT still operation](https://helpguide.sony.net/ilc/1820/v1/en/contents/TP0002240386.html)
describes half-press focus followed by full-press capture.

[α6700 BULB](https://helpguide.sony.net/ilc/2320/v1/en/contents/0302C_bulb.html)
explicitly distinguishes physical-camera hold from Bluetooth remote press-start /
press-stop. R2 maps the requested hold gesture to TWO complete Bluetooth presses,
not one held09. First09 and both first key-ups must be transport-accepted before
normal end. A failed/uncertain start is not blindly retried; partial cleanup is key-up
plus a real error warning. Unknown feedback is allowed with normal user intent and
camera setup instructions; it is not claimed as measured or confirmed exposure.
Disable native BULB Timer for app timing and coordinate any camera-side interruption
with the app. Physical/radio failure can leave exposure uncertain; inspect the body.

That model requires Still + M + BULB and compatible shutter/drive; Electronic Shutter,
continuous/bracket drive and native interval can prevent BULB. Model restrictions
must not be generalized to every Sony body. App mode selection sends no exposure or
drive configuration commands.

[Long Exposure NR](https://helpguide.sony.net/ilc/2320/v1/en/contents/0414_long_exposure_nr.html)
can block another picture during processing. Phone interval is foreground scheduling,
start-to-start with skipped elapsed slots, not camera-native interval configuration,
countdown selfie or movie assembly. UI trigger totals are not saved-photo counts.

## Lifecycle and safety

Remote ownership is separate from the per-operation GATT/GPS lane. Session identity
is captured once; stale writes/releases cannot target a replacement. Queue waiting
remains cancellable; accepted remote writes settle within callback bounds before
cleanup. Stop stays pinned outside scroll content, including when disconnected.
Mode/input/AF/movie actions are gated during another operation. Screen loss cancels
foreground sequences and attempts same-session cleanup; it does not toggle movie
recording off. Process death/radio loss is not solved by coroutine finally.

## Device checks (not implied by unit/build results)

1. Verify single/AF/movie and GPS again.
2. Burst bodyContinuous: hold1–3seconds, release, dragoff, rapid down/up, multitouch,
   back/lock/disconnect. Check no delayed press happens after early finger-up.
3. BULB bodyM/BULB/compatible drive/shutter/nativeTimerOff: short manual hold and2–3sec
   timer while watching the body. Confirm release/end and known-inactive suppression.
   If uncertain, end exposure on the camera instead of repeatedly pressing the app.
4. Interval:3 triggers at5seconds, stop between frames, slow exposure/processing, no
   catch-up burst. Actual capture depends on body settings and storage readiness.
5. Sliders/exact dialog: endpoints, short seconds, cancel/invalid/IME/valid submit,
   disable during run, Chinese/English, large fonts and narrow layouts.
6. Touch/accessibility/keyboard/disposal and fixed Stop visibility need device tests.

## Acceptance and release authorization

On 2026-10-28 the user reported R3 validation OK and explicitly requested committing
these changes, adding tag v1.0.0, and publishing a release. This authorizes the
corresponding commit/push/tag/release operation; it does not imply every optional
configuration or failure-path test above was individually exercised. Keep the
remaining hardware/visual coverage limits documented rather than claiming them all
verified. Future changes still require the user's normal validation/approval flow.
