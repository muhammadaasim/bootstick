# BootStick — code map

Rooted Android app: exposes `.iso`/`.img` files to a PC as USB disk / CD-ROM via configfs USB gadget.
Kotlin + Jetpack Compose (Material3), single module `app`, package `dev.aasim.bootstick`, minSdk 26.
Only extra dep: `org.tukaani:xz`. No DI, no ViewModel, no nav lib — keep it that way.

## Flow
UI (Compose screens) → `AppState` (MainActivity.kt) → `Engine` (Engine.kt) → `su -c sh bootstick.sh <cmd>` (assets/bootstick.sh) → configfs.
Downloads: GetScreen → `Downloader` (Downloader.kt) kept alive by `DownloadService`.

## Files (app/src/main/java/dev/aasim/bootstick/)
| File | What lives there |
|---|---|
| MainActivity.kt | `MainActivity`, `AppState` (all app state + actions: scan/attach/detach/check/blank), `App()` root composable, tabs, splash, intents `ACTION_MOUNT_LAST`/`ACTION_EJECT` |
| Engine.kt | `Engine` object: runs bootstick.sh as root (`run`, `scan`, `attach`, `detach`, `read`, `put`), persists wanted/last mounts + per-image mode in SharedPreferences (`mount`, `settings`). `Image`, `Mount`, `Output`, root-app detection |
| MountService.kt | Foreground `MountService` (keeps mount, reacts to `USB_STATE`, auto-mount), `BootReceiver`, `MountTile` (Quick Settings) |
| Inspect.kt | Pure logic: image kind detection (`kindOf`, `biosOnly`), mode suggestion, checksum (`hashesIn`, `algoFor`, `digest`), unpack xz/gz/zip, `createBlank` + `formatFat32`, size consts |
| Catalog.kt | `Distro`, `Catalog` (bundled assets/catalog.json, updated from aasim.site), `resolve` newest URL via pattern, `publishedChecksum` discovery, HTTP `get` |
| Downloader.kt | `Downloader` object: multi-segment (`Seg`) resumable downloads, `Task` state, queue (2 at a time), Wi-Fi-only, SD dir, verify + unpack on finish |
| DownloadService.kt | Foreground service hosting active downloads |
| DriveScreen.kt | Drive tab: hero/mount status, image library cards, auto-mount switch |
| GetScreen.kt | Get ISOs tab: catalog list, download tasks, settings |
| ToolsScreen.kt | Tools tab: check image, make blank image, boot guide, diagnostics |
| Ui.kt | Shared composables (`MountSheet`, `Pill`, `IconBadge`, `SectionTitle`), `Usb` state, formatting helpers |
| Onboarding.kt | 3-page intro + root check |
| About.kt | About screen |

## Other
- `app/src/main/assets/bootstick.sh` — the root engine. Commands: attach/detach/info/scan/kind/bios/diag/read/put/commit/rm/mv. Output is `key=value` lines.
- Image-kind logic exists twice: shell (`bootstick.sh kind`) and Kotlin (`Inspect.kt kindOf`). Change both; tests: `sh test-kind.sh` and `app/src/test/.../InspectTest.kt`.
- Strings: `res/values/strings.xml` + 8 locales (ar, fr, hi, it, pt-rBR, sd, tr, ur). New user-facing string → add to all.
- Build: `./gradlew assembleDebug`; unit tests: `./gradlew test`.
