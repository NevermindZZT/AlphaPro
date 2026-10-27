# Wi-Fi credential diagnostics (W2)

## Evidence boundary

The phone screenshot reports the application's credential-read failure, before
WifiNetworkSpecifier is requested. The camera starting its Wi-Fi confirms only
that CC08 reached the camera; it does not establish CC06/CC07 read status or bytes.
There is currently no device ATT trace, so no firmware timing, NUL termination,
MTU truncation, authentication failure, or malformed prefix is a confirmed cause
of the user's specific failure. Previous timeout-only explanations were hypotheses.

## Confirmed implementation defects addressed

- Global read/write deferreds did not match GATT identity, kind, or characteristic.
  A timed-out read's late callback could satisfy a subsequent different read.
- A bootstrap-wide mutex blocked location writes throughout all sleeps/retries.
  The new workflow holds only a separate Wi-Fi guard; each individual ATT operation
  shares a callback-backed serial lane with GPS and remote control.
- Remote writes previously released the lane as soon as Android accepted initiation.
  They now await Android's write completion (not evidence of a photo being taken).
- Old GATT disconnect callbacks could close the global replacement handle.
- Repeated UI taps queued independent bootstraps; disconnect did not cancel them.
- Credential parsing assumed prefix byte zero was always 0x03 and stripped spaces.

## Reference comparison

Compared with the local AlphaGPS checkout: SonyWifiBootstrap, BleOperationQueue,
AndroidBleTransport, CameraSessionOrchestrator and AndroidCameraNetworkRequest.
The reference prepares MTU before bootstrap, optionally subscribes and prereads CC09,
activates CC08 once, probes CC09, reads CC06/CC07, strips three protocol bytes and
validates SSID length 1..32 and password length 8..63. Unknown CC09 status exits
that probe; state 0 plus explicit errors 2..5 is a camera-reported failure.
A 45-second workflow deadline and 15-second operation timeout replace ad hoc
1/2-second timeouts. Trailing NUL support is an explicit compatibility extension;
printable spaces are preserved end-to-end, never silently filtered.

Do not copy the reference's Android busy=201 treatment as successful initiation.
Do not treat Android notification events as read responses.

## Recovery and safety

Callbacks have no ATT request sequence ID. On accepted-operation timeout or
cancellation, retire that connection rather than allowing a late same-UUID
callback to complete a retry. Automatic reconnect then reruns the unchanged GPS
handshake. This avoids stale replies but cannot promise uninterrupted GPS during
a genuinely stalled hardware operation. GPS packet encoding and heartbeat period
are not changed by this patch. Bootstrap polling delays do not hold its lane.

## Device verification

1. Install the W2 build, wait for BLE Ready, tap Wi-Fi once.
2. If it fails, use the persistent Wi-Fi diagnostic dialog's Copy diagnostic button.
   The report contains stage, UUID/instance, MTU, status, byte counts, elapsed time,
   and parsing/length validity, never SSID/password bytes, addresses, or keys.
3. Compare the last operation: MTU/CC09/CC08/CC06/CC07, initiation rejection,
   ATT status 5/15 (auth/encryption), timeout, or parser failure with returned bytes.
4. Do not infer authentication or firmware failure from a generic null result.
5. Once successful, repeat connect/disconnect, test camera power-cycle and GPS
   continuity during a failed attempt. Network association and PTP session opening
   are later, distinct stages.

## Validation result

The 35 JVM regression tests passed; Debug and Release builds and APK signature
verification passed. On 2026-10-28, the user confirmed repeated successful Wi-Fi
connections and camera photo-list refreshes using the W2 build, and authorized
committing the patch. This validates the combined fix on the tested setup; it does
not isolate which individual defect triggered the earlier device failure.
