# Contributing

Thanks for taking a look. This is a small, personal project — issues and pull
requests are welcome, but expect a slow and opinionated review.

## Getting a working checkout

See [Setup](README.md#setup) in the README. Two things are not in the repository
and must be provided once:

1. `local.properties` with your own `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` from
   <https://my.telegram.org>.
2. The TDLib native libraries: `bash app/src/main/jniLibs/fetch.sh`.

Never commit either of those, or a keystore. `.gitignore` covers all three.

## Before opening a PR

```bash
./gradlew test
./gradlew assembleDebug
```

Both must pass; CI runs exactly these.

## House rules

- **Match the surrounding code.** Comments here explain *why*, not *what* — a
  comment that restates the line below it will be asked about in review.
- **Analytics goes through the facade.** Nothing calls the PostHog SDK directly
  except [`analytics/Analytics.kt`](app/src/main/java/com/teletv/analytics/Analytics.kt),
  so that one file remains the complete answer to "what leaves the device". New
  events carry shapes and outcomes — counts, durations, booleans, error codes —
  never titles, file names, captions, search text, or Telegram ids.
- **Test the pure logic.** Tag extraction, split-video grouping, fuzzy search and
  the Pinyin index are I/O-free by design; changes there should come with tests.
- **TDLib version sensitivity.** Call sites whose `TdApi` signatures may differ
  across TDLib versions are marked `// TDLib-version-sensitive`. If you bump
  TDLib, re-fetch the Java bindings and the `.so` files from the same upstream
  commit and re-check those sites.
- **Remote-first UI.** Every control must be reachable with a D-pad. There is no
  touchscreen and no keyboard.

## Design docs

Larger features are proposed and specified under `openspec/` before they are
built. Reading the relevant `openspec/specs/<capability>/spec.md` is usually the
fastest way to understand why something behaves the way it does.

## License

By contributing you agree that your contributions are licensed under the MIT
License, the same as the rest of the project.
