# Third-party notices

TeleTV itself is MIT-licensed (see [LICENSE](LICENSE)). It bundles or depends on
the components below, which keep their own licenses.

## Vendored source

### TDLib Java bindings — Boost Software License 1.0

`app/src/main/java/org/drinkless/tdlib/Client.java` and `TdApi.java` are copied
verbatim from TDLib and carry their original copyright header:

> Copyright Aliaksei Levin (levlam@telegram.org), Arseny Smirnov
> (arseny30@gmail.com) 2014-2026
>
> Distributed under the Boost Software License, Version 1.0.
> (See <http://www.boost.org/LICENSE_1_0.txt>)

Upstream: <https://github.com/tdlib/td>. The specific copies here come from the
Telegram X prebuilt bundle at <https://github.com/TGX-Android/tdlib>, so that the
bindings match the object-style JNI client in the prebuilt `.so`.

## Native libraries (fetched, not committed)

`libtdjni.so`, `libcryptox.so` and `libsslx.so` are downloaded into
`app/src/main/jniLibs/<abi>/` during setup — see
[`app/src/main/jniLibs/README.md`](app/src/main/jniLibs/README.md). They are not
part of this repository and are not redistributed by it.

- **libtdjni.so** — TDLib, Boost Software License 1.0.
- **libcryptox.so / libsslx.so** — OpenSSL (renamed to avoid clashing with the
  platform's own copy), Apache License 2.0 (OpenSSL 3.x).

If you redistribute a built APK, it contains these libraries and you must ship
their license texts with it.

## Runtime dependencies

Declared in [`app/build.gradle.kts`](app/build.gradle.kts); all are fetched from
Maven and none are redistributed in source form here.

| Component | License |
| --- | --- |
| AndroidX (Compose, Compose for TV, Lifecycle, Activity, Core, Room, Annotation) | Apache-2.0 |
| AndroidX Media3 / ExoPlayer | Apache-2.0 |
| Kotlin, kotlinx-coroutines | Apache-2.0 |
| Coil (`io.coil-kt:coil-compose`) | Apache-2.0 |
| ZXing (`com.google.zxing:core`) | Apache-2.0 |
| PostHog Android SDK | MIT |
| JUnit 4 (test only) | Eclipse Public License 1.0 |

## Telegram

TeleTV is an unofficial, unaffiliated client. "Telegram" is a trademark of
Telegram Messenger LLP. Every user builds with their own `api_id`/`api_hash` from
<https://my.telegram.org> and is bound by Telegram's
[API Terms of Service](https://core.telegram.org/api/terms).
