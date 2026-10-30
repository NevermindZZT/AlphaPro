# Gallery H3 — historical repair evidence; HEIF priority superseded by H4

**Subsequent device feedback:** H3 broke both HEIF420/422 list thumbnails, while JPEG
still loaded; the user's device supports420 natively. H3's forced-software HEIF
routing was wrong for that device and is superseded by the platform-first byte/file
and companion-rendering policy in `GALLERY_H4_REVIEW.md`. The tests below did not
establish that this forced priority was correct or that Sony thumbnails worked.

## Correction

The user explicitly reported H2 still failed 4:2:2 previews/downloads and horizontal
paging. H2 compilation and its old JVM suite did not test those paths. Earlier
claims of complete resolution, instant previews, or specific 1–2MB/20MB camera
payloads were unsupported and are withdrawn. No representative user HIF or camera
trace was supplied this round, so the exact failing user payload is not proven.

## Specific defects corrected

1. **Preview decode:** H2 chose 64/256MiB budgets from encoded length even though
   libheif checks full source dimensions *before* downscaling. A 6000x4000 10-bit
   422 fixture deterministically fails the old 256MiB preflight (24MP*12 >256MiB).
   H2's >8MiB fallback also used an unspecified temporary directory and the full-
   resolution decode API, violating the preview edge bound. Its catch-all hid why.
   H3 probes actual HEIF brands (not every MP4/AVIF ftyp), selects software decoding
   for HEIF, serializes previews with a 512MiB budget, and streams a bounded original
   to an explicit app-cache file when necessary. New decodeFilePreview returns at
   most 2048px before Android Bitmap creation. Native crop/rotation is not applied a
   second time. Original files remain limited to32MiB; native source size/memory
   restrictions remain. Decoding still needs full source codec work before scaling,
   so this is not a promise of constant latency or arbitrary 61MP support.
2. **Cancellation/session handoff:** H2 clearPreview canceled without joining, and
   guardedIo classified cancellation during an accepted read as a fatal transport
   failure. Reconnect preflight could race that late close. H3 holds the transaction
   mutex until an accepted transaction consumes its matching response under bounded
   NonCancellable settlement (15s socket /30s transaction). Real protocol/I/O failure
   still closes transport; cancellation is propagated *outside* the fatal-close
   handler. A failed settlement of a canceled caller retains its real cause but
   propagates cancellation, not an unexpected timeout into its parent coroutine.
   Downloads and previews pin a camera-intent epoch; replacement/disconnect cannot
   redirect old handles. Only an idempotent partial read may retry once at the same
   remote offset, before any bytes from that attempt are written locally. Commands,
   captures and writes are never blindly replayed. Export waits for canceled preview
   jobs to settle. This can wait for the existing deadlines, not necessarily finish
   immediately. Decode callbacks propagate cancellation; stale results are discarded.
3. **Paging gestures:** H2 detectTransformGestures consumed one-finger drags even at
   fit scale. Per-page handlers also captured stale current-page flags, while two
   VM/pager effects could snap a live drag. H3 leaves fit-scale one-finger gestures
   to HorizontalPager, owns pinch/zoomed-pan through final pointer-up, stores transform
   state per stable capture ID, and reports only changed idle settled pages. Runtime
   testing additionally caught an Offset.Unspecified centroid access on pointer-up;
   the centroid is now checked *before* arithmetic. Selection/batch controls remain.

## Actual verification performed

- **265 default JVM results pass:** app54, Bluetooth124, PTP57, location13, heif17.
  New protocol cases use real loopback TCP, including canceled accepted preview to
  queued export on the same healthy connection, deadline failure/recovery, exact
  retry offsets/no duplicate bytes, and stale preview/export rejection on a
  replacement/disconnected camera. Initial deadline regression failed and exposed
  the cancellation-precedence issue above; it passed after the production fix.
- **Three production-viewer touch tests pass** under Robolectric SDK34, explicitly
  enabled with `-PandroidUiRegression=true -PnativeHeif=false`. These dispatch actual
  Compose touches through PhotoDetailViewerScreen, including left/right traversal
  through formerly offscreen pages, selection/batch callbacks, pinch/zoomed pan,
  reset-to-fit paging, and entry-index changes during an unfinished drag. Initial
  pinch test exposed the centroid exception; all three passed after correction.
  This is not a physical Android16 device test. The previous13 optional onboarding
  runtime tests were compiled but not executed this round. The passing viewer XML
  was copied to `.gradle/h3-evidence/viewer-gesture-passed.xml` before the default JVM
  task replaced app test reports.
- **Native CTest passes** against the same vendored libheif/libde265 decoder source.
  Synthetic fixtures are generated locally using FFmpeg/libx265, not user photos.
  The executable verifies real source10-bit420/422 chroma, both memory previews,
  reproduces H2's24MP budget rejection, successfully decodes/scales that24MP422 file
  to2048x1365, checks cancellation, malformed input and invalid budget rejection.
  These are8 logged scenarios within1CTest executable, not8Android/JNI tests.
- Final default-native arm64 Release and v2/v3 signature pass. APK guard verifies
  all3ARM64 libraries, five JNI entry symbols including file preview, dependency
  closure,16KiB ELF alignment and compressed/extracted library packaging.
- Localization427 keys, shared UI77calls/15files and whitespace checks pass.

## Reproduce

```sh
python3 scripts/generate-heif-fixtures.py  # optional: fixtures are also retained in src/test/assets
cmake -S core/heif/src/main/cpp -B .gradle/heif-host -DCMAKE_BUILD_TYPE=Release
cmake --build .gradle/heif-host --target heif_decoder_test -j2
ctest --test-dir .gradle/heif-host --output-on-failure
./gradlew :core:ptp:testDebugUnitTest :core:heif:testDebugUnitTest :app:testDebugUnitTest --offline --no-daemon
./gradlew :app:testDebugUnitTest -PandroidUiRegression=true -PnativeHeif=false --tests '*PhotoDetailViewerGestureTest' --offline --no-daemon
```

The optional UI run needs cached/downloadable Robolectric native runtime and SDK34.
It initially failed in offline mode for a missing runtime; enabling online dependency
resolution fetched it. No physical device was attached. The native host test does
not prove Android JNI loading, all Sony profiles, HDR color fidelity or the user's
camera behavior.

## Deliverable and acceptance

`app/build/outputs/apk/release/AlphaPro-1.0.0.20-gallery-preview-H3.apk`
(14,239,342bytes), SHA256
`3da13aca5ebb1447d4df633bd4ae31431d2ef5926c2ee56f5d9eb93da6cc62cf`.

VersionCode remains100000020 because H2/H3 are still uncommitted refinements atop
commit6fffca1. Cover-install without clearing data. Check the same422image while
loading and immediately start download; also swipe from the middle of a fitted
large image both directions, pinch/pan/reset and swipe again. If still failing,
retain the new error text/trace and optionally provide a non-sensitive original HIF
so the exact container/profile can be reproduced. Do not call the device issue
resolved before that acceptance. No commit, push, tag or Release operation performed.
