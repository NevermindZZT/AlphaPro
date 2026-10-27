# Gallery G2 validation notes

## Observed device results and evidence boundary

The G1 device screenshots show a correctly oriented 1080x1616 camera preview, but
its Dialog status area remains grey and its export button is clipped. Later grid
items are blank and their viewer reports "Camera session is closed". This proves
subsequent reads see a retired session, not which first camera packet caused it.

Confirmed source defects: G1 size/length checks threw before consuming the final
operation response and closed the shared TCP connection. Thumbnail loading then
swallowed that error. There was no transport recovery or per-photo retry state.
The triggering camera length, unknown-total sentinel or socket event remains
unconfirmed without a device packet trace.

## G2 implementation

- Stream object data through bounded chunks; discard over-limit data and consume
  the matching final response before reporting PtpDataException. Unknown total
  (-1) is allowed but actual bytes stay bounded. Wrong IDs, invalid framing and
  socket failure remain fatal. Preserve the first cause.
- Serialize session opening, initialization, reads, commands and reconnection.
  Handle command-lane PING/PONG. Explicit reconnect uses the saved host, port and
  Android Network and restores known catalog mode, without provisioning Wi-Fi or
  replaying capture/user writes. The image loader may retry one idempotent read.
- Show per-photo loading/error/retry, retry evicted visible thumbnails, and remove
  off-screen queued work without cancelling active reads just for scrolling.
- Replace the floating Dialog with an Activity-root full-size viewer, outside both
  Scaffolds. One Activity theme owner controls system-bar icons/contrast. Black
  draws behind bars; safeDrawing is applied once. Pin the export button outside
  scrolling metadata. Preserve gallery composition/scroll/filter while covered;
  block background interaction and accessibility, handle system Back explicitly.
- Keep filters outside loading/empty-result branches and provide Show all.
- Parse Sony localCreated as reference-defined epoch milliseconds encoded as UTC
  wallclock; normalize real ObjectInfo CaptureDate for legacy data. Never use a
  fixed, current, modification or guessed capture date. Unknown dates form an
  explicit final bucket. Date headers collapse/expand with stable keys.

## Regression and manual checks

Automated tests cover bounded discard through the final response, continued
successful reads on the same session, unknown lengths, first fatal cause, strict
IDs/framing, PING/PONG, reconnect coalescing and initialization serialization,
transfer-mode restoration, cancellation, EXIF orientation, actual Sony/ObjectInfo
dates, date ordering/unknown buckets and unchanged capture identities.

Device checklist (unit tests/build do not establish every Android device-mode combination):

1. Open a portrait preview: status/navigation backing black, white icons, close and
   export entirely visible in gesture and three-button modes; test landscape/font scaling.
2. Close/back: normal theme restored, grid scroll/filter/fold state retained.
3. Scroll beyond the initially visible photos; open a previously unseen photo.
   An object-local error must not disable the rest; retry should be actionable.
4. Choose zero-count HEIF: chips stay available and Show all returns immediately.
5. Expand/collapse multiple real dates; unknown dates are explicit, counts remain
   capture counts rather than duplicate RAW/rendered files.
6. Verify the existing dual-format export/HEIF choice flows and Wi-Fi/GPS stability.

## Acceptance

On 2026-10-28, the user confirmed that the G2 changes passed device validation and
authorized committing them. The 48 gallery/PTP tests passed and the 35 existing
BLE regressions remained passing; Debug/Release builds and APK signature checks
also passed. This records acceptance of the tested setup, not exhaustive coverage
of every navigation mode, screen size or camera firmware. The exact first camera
packet that triggered the original G1 session closure remains unconfirmed.
