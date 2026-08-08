# TDLib native libraries

These are the **Telegram X** prebuilt TDLib binaries (object-style JNI client),
matching the vendored Java bindings in `app/src/main/java/org/drinkless/tdlib/`
(`TdApi.java`, `Client.java`). Source: <https://github.com/TGX-Android/tdlib>.

## Fetching them

The `.so` files are Git-LFS blobs upstream and are **git-ignored here** — too
large to commit, and not ours to redistribute. A fresh clone has none of them and
will crash on launch with `UnsatisfiedLinkError` until you run:

```bash
bash app/src/main/jniLibs/fetch.sh
```

That downloads, into `<abi>/` directories it creates as needed:

```
arm64-v8a/     libtdjni.so  libcryptox.so  libsslx.so   <- real Android TV boxes (newer, 64-bit)
armeabi-v7a/   libtdjni.so  libcryptox.so  libsslx.so   <- real Android TV boxes (older, 32-bit)
x86_64/        libtdjni.so  libcryptox.so  libsslx.so   <- Android TV emulator (64-bit)
x86/           libtdjni.so  libcryptox.so  libsslx.so   <- Android TV emulator (32-bit)
```

`libtdjni.so` is loaded by `TdlibClient` via `System.loadLibrary("tdjni")`; it
links against the renamed OpenSSL libs `libcryptox.so` / `libsslx.so`, which are
loaded first (see `NativeLibs.ensureLoaded()` in `TdlibClient.kt`).

## Adding an ABI

Add it in two places, or the APK ends up with a silently-missing ABI slot:
`abiFilters` in `app/build.gradle.kts`, and the `abis` array in `fetch.sh`.

## Version note

The bindings and the `.so` come from the same upstream commit, so they always
match. If you bump to a different TDLib version, re-fetch **both** the Java
bindings and the `.so` files together.

## Licensing

`libtdjni.so` is TDLib (Boost Software License 1.0); `libcryptox.so` /
`libsslx.so` are OpenSSL (Apache-2.0), renamed to avoid clashing with the
platform's own copy. See [`THIRD-PARTY.md`](../../../../THIRD-PARTY.md) — if you
distribute a built APK, it contains these and must ship their license texts.
