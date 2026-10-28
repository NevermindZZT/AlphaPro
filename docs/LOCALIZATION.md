# Localization

## Supported choices

- System default: no app-specific locale override.
- Simplified Chinese: BCP47 `zh-Hans`, resources under `values-zh`.
- English: BCP47 `en`, default resources under `values`.

English is the fallback for an unsupported device language. Device names, filenames,
protocol identifiers, URLs and technical diagnostic payloads are not translated.

## Implementation

MainActivity and SettingsActivity use AppCompatActivity with a compatible no-actionbar
theme. AppCompatDelegate application locales integrate with Android13+ system app
language settings and handle activity recreation on older versions. The app language
section is in Settings. An explicit preference also lets cold-start background
services resolve resources before any Activity delegate has been created on API26-32.

AppLanguage.localizedContext/getString is used for application/service contexts.
The running GPS service refreshes notification/channel copy when the app language
changes; it does not restart the GPS loop or reconnect the camera for this refresh.
AppCompat stores locale preferences; Android13+ LocaleManager is authoritative.

Navigation/preference enums keep resource IDs, not translated strings. The current
main destination is saveable across locale recreation. Cached preview captions and
errors use LocalizedText resource identities so retained bitmaps do not retain old
translations. Gallery date keys and media identifiers stay unchanged; display dates,
sizes, percentages and plural quantities use the current app configuration locale.

## Resource organization

Domain files: strings_control, strings_gallery, strings_settings, strings_runtime,
strings_locale, strings_preview and strings_network. Bluetooth display errors have
library-local resources. New languages can add corresponding qualified resource
files and then a supported locale entry/choice without changing protocol code.
Do not build sentences by concatenating translated fragments. Use indexed Android
format arguments and plurals. Native language names and the AlphaPro brand are
intentionally nontranslatable.

## Checks

```sh
python3 scripts/check-localization.py
node scripts/check-ui-components.mjs
./gradlew :app:testDebugUnitTest :core:bluetooth:testDebugUnitTest :core:ptp:testDebugUnitTest
```

The localization guard checks XML validity, duplicate/missing Chinese keys, format
argument parity, required plural fallback, and hardcoded visible labels. JVM tests
check supported locale/alias mapping. Builds check AAPT resources and references.
These checks do not establish actual platform locale persistence or visual layout.

## Android-device verification checklist

1. Set English from a Chinese device, return to Dashboard/Gallery/Remote, then reopen
   Settings. Check the selected language, dialogs, accessibility descriptions, notices,
   notifications and date/quantity formatting.
2. Choose Simplified Chinese on an English device; restart the app and verify persistence.
3. Choose System, change device language, reopen/restart and verify fallback behavior.
4. On API33+, change the app language from Android Settings and verify synchronization.
5. On API26-32, cold-start the GPS service/camera presence before opening an Activity
   and verify selected-language notification copy. Camera linking must remain stable.
6. Check large fonts/narrow layouts, theme preservation and an already open preview.

## Acceptance

On 2026-10-28, the user accepted the L1 package and explicitly authorized committing
the localization changes. Resource and UI consistency checks, 87 JVM regression /
language-option tests, Debug/Release builds, and APK signature verification passed.
This acceptance does not establish exhaustive coverage of every Android version or
configuration in the checklist above.
