# HIF export crash — H1 native-enabled test build

## Confirmed failure

The user's Android crash is an uncaught `UnsatisfiedLinkError` at
`HeifDecoder.decodeFile`, not a corrupt-file diagnosis. The prior Gradle configuration
commented out native compilation, released APKs contained no `libalpha_heif.so`,
and the Kotlin object swallowed a failed `System.loadLibrary` before exposing
unconditional external calls. Catching `Exception` in the ViewModel did not catch
this `LinkageError`.

A separate C++ compile defect was also present: current-package JNI aliases called
legacy-package functions before any declaration. The real implementations now use
the current package and `nativeDecodeFile`, `nativeDecodeThumbnail`,
`nativeDecodePreview`, `nativeReadExif` names directly. JNI consumer rules retain
the class, native methods and static `createBitmap` callback.

## Changes under verification

- All four native operations go through `NativeHeifRuntime`: library-load failure
  and missing JNI exports become `HeifDecoderUnavailableException` (an IOException),
  rather than an uncaught VM error. Unrelated VM errors and coroutine cancellation
  are not broadly swallowed.
- Export checks capability before downloading. If unavailable, the HIF export sheet
  defaults to Original HIF and disables JPEG conversion with an actionable message.
  This is a compatibility guard, not a claim that unavailable builds can convert.
- Full-resolution conversions are serialized. Coroutine cancellation is passed to
  the decoder. Temporary HIF/JPEG files are cleaned on success/failure/cancellation.
- A completed JPEG must now be copied into a pending MediaStore row, closed and
  published before reporting success. Previously the conversion branch only wrote
  to the cache and nevertheless said it had exported. Failed publication removes
  the incomplete row. This Android flow still needs on-device verification.
- Native Gradle builds are now enabled by default, requiring NDK 26.1.10909125 and
  SDK CMake 3.22.1. Only explicit `-PnativeHeif=false` builds omit the decoder and
  receive the capability-error behavior. Source libraries are vendored; toolchains
  are not. Initial SDK inspection found only an old NDK installer marker. After
  host disk became available, the NDK/CMake installation succeeded and the real
  three-ABI native build was started. No toolchain or generated .so is committed.
- Linker 16KB page alignment is applied to alpha_heif, heif and de265 targets.
  With AGP 8.4, native libraries are compressed/extracted (`useLegacyPackaging`),
  avoiding direct mmap from ZIP entries without reliable 16KB ZIP alignment.
  `scripts/check-heif-apk.py` verifies packaged ABI files, JNI exports, required
  dependencies, ELF LOAD alignment and compressed entries after the final build.

## Validation boundaries

Nine Android-free JVM tests cover loader failure, missing JNI method, fail-fast
preflight, success, concurrent initialization, propagated decoder IO error,
cancellation, recoverable decode allocation failure and unrelated VM errors.
They do not decode a real HIF or prove JNI runtime/Android MediaStore behavior.
The final default-native build passed all 244 JVM results: app50, Bluetooth124,
PTP48, location13 and heif9. The 13 opt-in Android runtime tests remain unrun.

## Latest arm64-only artifact (supersedes the initial three-ABI build)

The user explicitly requested retaining only `arm64-v8a`. Both the app packaging
filter and HEIF native build filter now select only this ABI. The APK guard also
rejects any additional packaged ABI instead of ignoring it. The initial three-ABI
evidence below is historical, not the current configuration.

- Release rebuild succeeded; APK contains exactly three libraries, all under
  `lib/arm64-v8a/`: libalpha_heif.so, libheif.so and libde265.so.
- JNI exports, dependency closure, 16KB ELF alignment, extracted packaging and
  v2/v3 signing passed. An extra-ABI negative probe is correctly rejected.
- Current APK: `app/build/outputs/apk/release/AlphaPro-1.0.0.18-hif-native-H1-arm64.apk`
  (14,219,809 bytes), SHA256
  `f3952b8ca9e28b03773cfcd45a6da5129a3e9b8188c78c47fdd88f88a04126f0`.
- No Kotlin/native decode algorithm changed in this ABI-only revision. The prior
  244 JVM results remain the regression baseline; they were not rerun for filtering.
  Native-device verification limits remain unchanged.

## Initial three-ABI artifact evidence (historical)

- NDK 26.1.10909125 and SDK CMake 3.22.1 installed successfully; no downloaded
  toolchains, generated libraries or signing credentials are included in git changes.
- All three ABIs compiled successfully: arm64-v8a, armeabi-v7a, x86_64. Final build
  used the default native-enabled configuration (not merely the property override).
- The APK contains all nine expected .so files (alpha_heif/heif/de265 per ABI).
  Artifact guard verified all four JNI exports, correct ELF machines, every
  non-system DT_NEEDED dependency present, >=16KB LOAD alignment and extracted-
  library packaging. This is static packaging evidence, not successful device loading.
- Release build and v2/v3 signing passed; localization424, UI74 calls/15files and
  whitespace checks passed. Vendor 32-bit precision/NEON warnings and existing
  Android metrics/deprecation warnings were non-fatal.
- H1 APK: `app/build/outputs/apk/release/AlphaPro-1.0.0.18-hif-native-H1.apk`
  (16,959,541 bytes), SHA256
  `a9fc8f18f48772debea91cc5f882331fd53ef022b7475a1944398fc173bc352d`.
- No attached Android device or representative Sony HIF was used. Gallery visibility,
  JNI decode success, color/orientation and actual cancellation flows require the
  device checks below; neither JVM results nor ELF exports prove these.

Do not silently substitute Android platform decoding and claim support for Sony
10-bit 4:2:2, or claim full-resolution/correct-color decoding from JNI-symbol presence.
The decoder returns 8-bit RGBA/JPEG, not lossless 10-bit/HDR. EXIF is an allowlist,
not all metadata. The present export budget rejects sufficiently large images
(approximately above 44.7MP); 61MP support, EXIF-only rotation and HDR/wide-gamut
fidelity require additional work and fixtures. No representative user Sony HIF
has been provided for this round.

## Device acceptance checklist

1. On the original crash device, export a Sony HIF as JPEG; verify no crash and a
   new JPEG exists in the system gallery, can be reopened, and has sensible dimensions.
2. Compare orientation, color and selected EXIF fields against the original/reference;
   include both landscape/portrait and SDR/HLG cases. Do not infer tone-map fidelity.
3. Export Original HIF separately and check the saved file without conversion.
4. Export multiple images, cancel/leave during export, and test a malformed/oversized
   file: failure must not be reported as success or leave partial public JPEGs.
5. A deliberately native-disabled build should explain unavailability instead of
   crashing, including in Coil decoding paths.

The user subsequently requested committing H1 together with the arm64-only revision.
This authorizes a local commit, not a push, tag or Release update. No additional
native-device or image-fidelity test evidence was provided with that authorization;
the validation limits above remain applicable.
