# Contributing to BootStick

First off, thank you. BootStick is a small project, and every phone report, translation and fix makes it work for more people.

You don't need to know Kotlin or Android to help. Three of the four ways to contribute need no code at all:

| I want to… | Time it takes | Skills needed |
|---|---|---|
| [Report how BootStick works on my phone](#report-your-phone) | 5 minutes | A rooted phone |
| [Translate the app into my language](#translate-the-app) | An evening | Your language |
| [Fix or add a download in the ISO list](#fix-or-add-an-iso-download) | 15 minutes | Editing a JSON file |
| [Fix a bug or build a feature](#change-the-code) | Varies | Kotlin, or shell for the USB side |

Not sure where to start? Look at the issues labelled [`good first issue`](https://github.com/muhammadaasim/bootstick/labels/good%20first%20issue), or just [open an issue](https://github.com/muhammadaasim/bootstick/issues/new/choose) and ask.

By contributing, you agree that your work is released under the project's [MIT License](LICENSE).

## Report your phone

Honestly, this is the most valuable thing you can do. So far BootStick has been tested on one phone, and every kernel does USB a little differently. A report from a phone nobody has tried yet helps, **whether it worked or not**.

1. Install BootStick and give it root.
2. Try mounting an ISO and booting a computer from it.
3. Open **Tools → Diagnostics** and tap **Copy** or **Share**.
4. [Open an issue](https://github.com/muhammadaasim/bootstick/issues/new/choose) with:
   - your phone model and Android version,
   - your root manager (KernelSU, KernelSU Next, Magisk or APatch),
   - what worked and what didn't,
   - the diagnostics report.

Working phones get added to the compatibility table in the README.

## Translate the app

BootStick is in English, Arabic, French, Italian, Brazilian Portuguese, Turkish, Hindi, Urdu and Sindhi. More languages are very welcome, and so are fixes to the existing ones.

All the text lives in `app/src/main/res/values*/strings.xml`. English (`values/strings.xml`) is the source.

**To add a language:**

1. Copy `values/strings.xml` to `values-<code>/strings.xml`, for example `values-de` for German or `values-es` for Spanish.
2. Translate the text between the tags. Leave the `name="…"` parts alone.
3. Add your language code to `app/src/main/res/xml/locales_config.xml`.

**A few things to keep in mind:**

- Keep every placeholder exactly as it is: `%1$s`, `%2$s`, `%1$d`. They're replaced with file names, sizes and numbers.
- Write apostrophes as `\'` (for example `l\'image`), or Android will refuse to build.
- Don't translate the names BootStick, KernelSU, Magisk, APatch, ISO and USB.
- Aim for how a friend would explain it, not a word-for-word translation.

Fixing one wrong word in an existing translation is a perfectly good pull request.

## Fix or add an ISO download

The list in the **Get ISOs** tab is [`app/src/main/assets/catalog.json`](app/src/main/assets/catalog.json). Distributions sometimes move their downloads, and when they do, the link breaks until someone updates this file.

An entry is either a direct link to a file, or a page plus a `pattern` that picks the newest matching file. The pattern means a new point release (26.04.1, 13.7.0…) needs no change at all. The `about` field at the top of the file explains every option.

**Rules for entries:**

- Every URL must start with `https://`.
- Only free, open-source systems, downloaded from their official servers. No mirrors of unknown origin.
- Raise `version` at the top of the file whenever you change it, so installed apps pick up your copy.
- Run `./gradlew testDebugUnitTest` before opening a pull request. It checks that the file parses and every pattern is valid.

## Change the code

### Set up

You need **JDK 26** and the **Android SDK with platform 37**. Android Studio works, but so does the command line:

```sh
git clone https://github.com/muhammadaasim/bootstick.git && cd bootstick
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Run the tests

```sh
sh test-kind.sh               # bootstick.sh: image kinds and path rules (runs on macOS or Linux)
./gradlew testDebugUnitTest   # Kotlin: the same rules, plus sizes, checksums, unpacking, FAT32 and the catalog
```

Both run automatically on every pull request, so you'll see if something breaks.

### Find your way around

| File | What it does |
|---|---|
| [`assets/bootstick.sh`](app/src/main/assets/bootstick.sh) | Everything done as root: USB gadget setup, attach and detach, scan, file actions |
| `Engine.kt` | Runs `bootstick.sh` through `su` and remembers what's mounted |
| `MainActivity.kt` | `AppState`: the state and actions every screen shares |
| `MountService.kt` | Keeps images mounted and re-inserts them; the notification and Quick Settings tile |
| `Downloader.kt`, `Catalog.kt` | Resumable downloads, the ISO catalog, checksum lookup |
| `Inspect.kt` | Image kind detection, checksums, unpacking, blank images and FAT32 |
| `Theme.kt` | Colors, shapes and type for light and dark mode |
| `DriveScreen.kt`, `GetScreen.kt`, `ToolsScreen.kt`, `About.kt`, `Onboarding.kt` | The screens |

The Kotlin files are in `app/src/main/java/dev/aasim/bootstick/`. For the USB side, read [How it works](README.md#how-it-works) in the README first; it'll save you a lot of digging.

### How we keep things

- **Keep it small and plain.** No dependency-injection framework, ViewModels or navigation library. Shared state lives in `AppState` as plain Compose state. Please don't add a library for something a few lines can do.
- **The image-kind rules exist twice:** `kind` in `bootstick.sh` and `kindOf` in `Inspect.kt`. If you change one, change the other, plus the shared test cases in `test-kind.sh` and `InspectTest.kt`.
- **Every new piece of on-screen text goes into all languages.** Add it to `values/strings.xml` and to every `values-*/strings.xml`. If you can't translate it, put the English text in; translators will find it.
- **Root actions go through `bootstick.sh`**, which only accepts image files and refuses to touch a mounted one. Keep those checks.
- **Use the theme's colors** (`MaterialTheme.colorScheme`), never hard-coded ones, so light and dark mode both stay readable.
- **Try mounting changes on a real phone**, and say which one in your pull request. Emulators can't act as a USB drive.

### Opening a pull request

Small, focused pull requests get reviewed fastest. Before you open one, check:

- [ ] `sh test-kind.sh` and `./gradlew testDebugUnitTest` pass
- [ ] New text is in every `values-*/strings.xml`
- [ ] Mounting changes were tried on a real phone (which one?)
- [ ] The description says **why** the change is needed, not just what it does

Not finished yet? Open it as a draft pull request and ask for early feedback. That's very welcome.

## Releases (for the maintainer)

Releases are built and signed by [`.github/workflows/release.yml`](.github/workflows/release.yml). To publish one, raise `versionCode` and `versionName` in `app/build.gradle.kts`, commit, and push a matching tag:

```sh
git tag v1.1 && git push origin main v1.1
```

The workflow checks that the tag matches `versionName`, runs the tests, signs the APK with the key stored in the repository secrets (`BS_KEYSTORE_BASE64`, `BS_KEYSTORE_PASSWORD`, `BS_KEY_ALIAS`, `BS_KEY_PASSWORD`), and publishes `BootStick-v1.1.apk` with its SHA-256 as a GitHub Release.

---

Questions, ideas, or just want to say it worked on your phone? [Open an issue](https://github.com/muhammadaasim/bootstick/issues/new/choose). Every message gets read.
