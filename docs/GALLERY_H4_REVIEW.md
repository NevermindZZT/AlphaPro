# Gallery H4 — restore platform-first HEIF display

## Regression and uncertainty

The user reported that H3 broke both HEIF420/422 list thumbnails while JPEG still
loaded, and that their device supports420 natively. H3's new unconditional HEIF
software branch bypassed a functioning platform decoder; its file preview also
forced software whenever metadata said HEIF. Those priorities were wrong and have
been removed. The screenshot does not reveal the returned camera payload, size,
source resolution or decoder error; it is not proof of the sole failure cause.

## H4 behavior

- Both byte-array and temporary-file display paths try BitmapFactory first, then
  system ImageDecoder on API28+ when needed. A successful platform result is final:
  the software library/capability is not touched. Only a failed system path may use
  the bundled HEIF software decoder. System ImageDecoder applies its own orientation;
  the EXIF matrix is not applied again. Sampling precedes system Bitmap allocation.
  The software decoder retains its prior memory/source-size restrictions.
- The list requests camera-rendered small thumbnails first. If the visual file's
  thumbnail read, empty data or platform decode fails, it can try the same group's
  RAW rendering (not the full ARW). It can then try one bounded camera large rendering.
  Only after these platform candidates fail does it try software HEIF decoding,
  without retrying identical encoded data repeatedly. No full-original pixel-read or
  JPEG-export callback is part of this pipeline. The existing optional <=64KiB
  parent-orientation metadata read remains; that is not a full-original preview or
  a conversion. Cache identity remains the visual-file handle and camera epoch.
- Successful RAW-rendered fallback is not guaranteed: a camera may refuse it or
  return another unsupported format. All exhausted source/stage errors remain
  available by tapping the broken-image button. Details are selectable/copyable;
  no new persistent diagnostic logger is introduced.
- Large-preview captions explicitly identify system/software display decoding and
  temporary original preview versus export. Decoding produces display pixels, not
  a saved JPEG. Only explicit export/transcode actions call HeifTranscoder and
  MediaStore publication; original camera/source files are not rewritten by preview.
- Platform decode is not a claim of hardware acceleration. ImageDecoder's software
  Bitmap allocator controls the output allocation, not whether a device codec uses
  hardware internally. Device HEIF420 capability still needs the user's exact file
  on that device; host runtimes cannot emulate that capability.

## Tests and honest limits

- Eight production policy tests cover platform success without inspecting software,
  file-path priority, single fallback, cancellation and allocation failure.
- Ten thumbnail pipeline tests cover primary success/input unchanged, companion RAW
  priority, empty/read/decode failure, camera-rendered fallback, software ordering,
  duplicate suppression, error source/stage preservation, cancellation and OOM policy.
- Four Android runtime tests exercise production JPEG sampling, HEIF-tagged file
  metadata not forcing native, EXIF rotation once, unchanged encoded/file bytes, and
  displaying a real companion JPEG after a deliberately invalid primary. They use
  Robolectric SDK34 and native graphics, not a physical phone or a real Sony422 file.
  The first test fixture attempted Java AWT and could not compile on Android's
  bootclasspath; it was replaced by test-only Android Bitmap JPEG generation. Legacy
  ImageDecoder also returned a placeholder for invalid data, so native graphics mode
  is required instead of weakening the rejection assertion. Final outcomes below.
- The three H3 production-viewer touch tests are retained to check paging behavior.
- H3's synthetic real10-bit420/422 native decoder evidence and PTP loopback evidence
  remain separate; they did not establish mobile hardware support or prove H3's
  thumbnail routing was correct. No physical device or original user HIF is available.

## Final evidence and test package

- Default-native arm64 Release build and v2/v3 signing passed. Artifact guard checks
  the three ARM64 libraries, five JNI exports, dependencies and16KiB alignment.
- All283 default JVM results passed (app72/Bluetooth124/PTP57/location13/heif17).
  Separately, all7 runtime results passed:4 native-graphics decoder/EXIF/input-
  immutability/companion tests and3 production-viewer touch tests. Passed XMLs were
  preserved under `.gradle/h4-evidence` before default reports were replaced.
  The existing13 optional onboarding runtime tests were not executed.
- Localization434 keys, UI79calls/15files and whitespace checks passed.
- APK: `app/build/outputs/apk/release/AlphaPro-1.0.0.20-gallery-thumbnails-H4.apk`
  (14,254,044bytes), SHA256
  `be52278204f7a48adc04c8872187cea72e84c53d4682bc5f34022b53c17a9793`.
- No original Sony420/422 file or physical device was used. The HEIF-tagged-file
  runtime regression deliberately uses a platform-readable JPEG rendering; the
  companion test uses a deliberately invalid HEIF header followed by an actual JPEG.
  These validate routing/immutability, not the exact user HEIF or mobile hardware codec.

Cover-install H4 without clearing data. Check both HEIF cells and look for system
versus software labels in large preview. If a cell still fails, tap its broken-image
button and copy the source/stage error; an optional non-sensitive original HIF can
help reproduce the exact profile/container. Existing size/memory limits remain.

On2026-10-30 the user reported “验证OK，提交所有改动”, accepting the finalH4 and
authorizing a local commit of the complete H2/H3/H4 work, tests/docs and existing
build-environment path adjustments. No push, tag or Release operation is authorized.
This general acceptance does not establish per-model/HDR/large-image/failure-path
coverage; developer-side sample/device and budget limitations above remain.
At commit-time review, default283-result reports were still available, but the
ignored temporary `.gradle/h4-evidence` XML copies were no longer present. The seven
runtime passes above are the previously executed session evidence, not a new run
for this commit; test sources and reproduction details remain versioned.
