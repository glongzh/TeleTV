# TeleTV

An Android TV app that turns Telegram chats into a full-screen, remote-driven
photo/video library. Built with Kotlin, Compose for TV, TDLib and ExoPlayer
(Media3).

Unofficial and unaffiliated with Telegram. You build it with your own API
credentials; there is no hosted service and nothing is uploaded anywhere.

## Features

- **QR-code login** — scan with the Telegram app on your phone, no typing on the
  remote. Two-factor (cloud password) accounts are supported: a PIN pad appears
  for the password step.
- **Any chat as a source** — Saved Messages is the default, and any joined
  chat/channel can be picked instead. Chats with content protection are shown as
  non-selectable.
- **Grid browsing** with thumbnails, resume markers and paging as you scroll.
- **Tag filter panel** — tags are mined locally from file names and captions
  (series codes, terms, performer names, year) into a Room index; no text ever
  leaves the device.
- **Search** — fuzzy matching with Pinyin support for CJK titles.
- **Split-video grouping** — `part1`/`CD2`/`_003`-style volumes are detected and
  played as one continuous item.
- **Playback progress memory** — resumes where you stopped.
- **PIN lock** — an optional gate on app launch.
- **Proxy support** — SOCKS5 and MTProto, configurable before login, with a
  test-before-save ping.
- **Storage management** — cache size cap and manual clear.

### Remote controls

| Screen | Key | Action |
| --- | --- | --- |
| Player | **UP / DOWN** | previous / next item |
| Player | **OK** | play / pause |
| Player | **LEFT / RIGHT** (tap) | previous / next item |
| Player | **LEFT / RIGHT** (hold) | scrub backward / forward, accelerating |
| Player | **BACK** | back to the grid |

Videos with `supports_streaming` start instantly via a custom `TdlibDataSource`
that streams from TDLib's chunked download; other videos download first (with a
progress indicator) and then play.

## Setup

You need Android Studio (or a JDK 17 + the Android SDK) and an Android TV device
or emulator image.

### 1. Telegram credentials (required)

At <https://my.telegram.org> → *API development tools*, create an application and
copy its `api_id` / `api_hash`. Then:

```bash
cp local.properties.sample local.properties
```

and fill in `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`. `local.properties` is
git-ignored. Without these, TDLib cannot initialize and the app shows an error on
launch.

### 2. TDLib native libraries (required)

The Java bindings (`TdApi.java`, `Client.java`) are vendored under
`app/src/main/java/org/drinkless/tdlib/`, but the matching `.so` files are too
large to commit and must be fetched once:

```bash
bash app/src/main/jniLibs/fetch.sh
```

See [`app/src/main/jniLibs/README.md`](app/src/main/jniLibs/README.md) for what
this downloads and how to add an ABI.

### 3. Build and run

```bash
./gradlew :app:installDebug
```

First launch shows a QR code; scan it once and the session persists.

## Release builds

Release builds are signed only if you supply your own keystore. Create one:

```bash
keytool -genkeypair -v -keystore keystore/release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias teletv
```

then set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in
`local.properties` (see `local.properties.sample`). Both `keystore/` and
`local.properties` are git-ignored — **back them up**, since upgrading an
installed app requires the same key and a higher `versionCode`.

If no keystore is configured, `assembleRelease` still builds; the APK is simply
unsigned.

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Install on the TV with a USB stick and a file manager, or over the network:

```bash
adb connect <tv-ip> && adb install -r app-release.apk
```

## Analytics

Optional, off by default in any build you make yourself. PostHog sits behind a
single facade — [`analytics/Analytics.kt`](app/src/main/java/com/teletv/analytics/Analytics.kt) —
and nothing calls the SDK directly, so one file decides what leaves the device.

**`POSTHOG_API_KEY` is not in the repository.** A fresh clone compiles an empty
key, which means the SDK is never initialized: no network, no queue, every call a
no-op. You only get analytics if you deliberately add your own project key.

**What is sent** — shapes and outcomes only:

| Event | Properties |
| --- | --- |
| `$application_installed` / `_updated` / `_opened` / `_backgrounded` | SDK defaults |
| `$screen` | `Grid` \| `Player` \| `Settings` \| `SourcePicker` |
| `login_qr_shown`, `login_password_required`, `login_completed` | — |
| `login_failed` | `reason` — a fixed TDLib error string such as `TDLib error 400: PHONE_NUMBER_INVALID`, or one of the app's own constant messages |
| `signed_out` | — |
| `playback_started` | `media_type`, `duration_sec`, `size_bytes`, `streaming`, `is_group`, `part_count`, `resumed` |
| `playback_completed` | `duration_sec`, `is_group` |
| `playback_failed` | `error_code`, `media_type`, `streaming` |
| `search_performed` | `query_length`, `result_count`, `has_results` |
| `filter_applied` / `filter_cleared` | `category`, `match_count` / `kind` |
| `source_switched` | `indexed` |
| `index_scan_requested` | `mode` |

Every event also carries `build_type`, so debug runs are easy to exclude.

**What is deliberately never sent**: media titles, captions, file names, chat or
source names, tag text, search queries, Telegram user/chat ids. `identify()` is
never called, so events stay on PostHog's anonymous device id; signing out calls
`reset()` for a fresh one. Session replay, feature flags and remote config are all
off — nothing here would record a 10-foot UI usefully, and the app must not make
blocking startup requests on a restricted network.

**Opt-out**: *Settings → Usage data* toggles sharing on the TV itself; it applies
immediately and survives sign-out. (PostHog's own opt-out flag lives in preferences
that `reset()` wipes, so the choice is persisted by the app instead.) The entry is
hidden entirely when no key is compiled in.

**Networking caveat**: PostHog talks straight to its own host and does *not* go
through the app's TDLib proxy. Where Telegram is blocked, events queue and are
eventually dropped; playback is never affected.

## Project layout

```
app/src/main/java/com/teletv/
    ServiceLocator.kt           manual DI graph + app-wide wiring
    analytics/Analytics.kt      PostHog facade + event/screen name constants
    tdlib/TdlibClient.kt        coroutine/Flow wrapper over TDLib JNI
    auth/                       QR login state machine, 2FA password step
    media/                      media stream, pagination, source selection
        index/                  Room tag index, tag/name extraction, grouping
        search/                 fuzzy matcher + Pinyin index
    player/
        TdlibDataSource.kt      streaming spine (ExoPlayer <- TDLib download)
        PlayerViewModel.kt      media list, current item, playback
    proxy/                      SOCKS5 / MTProto proxy management
    security/                   PIN lock
    storage/                    cache cap, usage reporting, clearing
    ui/                         Compose-for-TV screens
app/src/main/java/org/drinkless/tdlib/   vendored TDLib bindings (BSL-1.0)
openspec/                       design docs and change history
```

## Development notes

```bash
./gradlew test        # unit tests (tag extraction, grouping, search)
```

`TdApi` constructor signatures target a recent TDLib; call sites that may need
adjusting for another version are marked `// TDLib-version-sensitive`. If you bump
TDLib, re-fetch the Java bindings and the `.so` files together — they must come
from the same upstream commit.

## Known limitations

- Photos and videos only; GIFs are skipped.
- No prefetch — each item loads when it becomes current.
- HTTP proxies are not exposed in the UI (SOCKS5 and MTProto only).
- Release builds do not run R8/ProGuard (`isMinifyEnabled = false`).

## License

MIT — see [LICENSE](LICENSE). Bundled and vendored third-party components keep
their own licenses; see [THIRD-PARTY.md](THIRD-PARTY.md).
