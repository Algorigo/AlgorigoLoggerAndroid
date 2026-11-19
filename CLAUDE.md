# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin Android logging library built on `java.util.logging` (JUL). Hierarchical `Tag` objects map to JUL loggers, and the library adds handlers that write to logcat, rotating files (with optional S3 upload), AWS CloudWatch Logs, and Datadog. The library module `algorigo_logger` is published to **Maven Central** as `com.algorigo.library:algorigo-logger` (version `versionStr` in `algorigo_logger/build.gradle.kts`). Other company libraries consume it, for example `algorigoble2` exposes it as an `api` dependency. The Kotlin package is `com.algorigo.logger`. The Android `namespace` is `com.algorigo.library.algorigo_logger`, which is a different string, so don't "fix" one to match the other. There are no real tests (only generated `Example*Test` stubs).

Modules: `algorigo_logger/` (library), `app/` (Compose sample: logcat + rotating file + S3 + CloudWatch), `datadoglogapp/` (sample for the Datadog path). Both samples depend on `project(":algorigo_logger")`.

Toolchain: AGP 9.2 with built-in Kotlin (no `kotlin-android` plugin; `kotlin { compilerOptions }` sets JVM 17), Gradle 9.4.1, compileSdk 37, minSdk 24. `gradle.properties` disables the Gradle daemon.

## Commands

```bash
./gradlew :algorigo_logger:build              # what CI runs (with --no-daemon --max-workers=2)
./gradlew :algorigo_logger:assembleRelease    # AAR → algorigo_logger/build/outputs/aar/algorigo_logger-release.aar
./gradlew :app:installDebug                   # sample app
./gradlew :datadoglogapp:installDebug         # Datadog sample
./gradlew :algorigo_logger:assembleRelease :algorigo_logger:publish   # → algorigo_logger/build/staging-deploy
```

### Publishing
- The version is `<versionStr>-<user.name>-SNAPSHOT` unless the current git branch name contains `master`. Either way, `publish` only writes to the local `build/staging-deploy` repository (named `stagingDeploy`). No Nexus properties are involved anymore.
- The publication attaches the **pre-built** release AAR (`artifact(...)` inside `pom {}`), so run `assembleRelease` first. There is no `from(components...)`, so **the POM declares no dependencies**. Consumers must add whatever the code paths they use need: `kotlin-reflect` (always, because `Tag` uses `objectInstance`), RxJava3/RxRelay3 (`RotatingFileHandler` and `CloudWatchHandler`), the AWS SDKs (`aws-android-sdk-logs`, `-s3`, `-cognito`), and `dd-sdk-android-logs`.
- Publications are signed with the `signing` plugin. CI fetches the key ring to `algorigo_logger/secret-keys.gpg` from `$SECRET_KEY_RING_PATH` and `~/.gradle/gradle.properties` from `$GRADLE_PROPERTY_PATH`.
- The `org.jreleaser` plugin is applied, but its whole configuration block is commented out. The CircleCI curl steps do the actual upload.

### CI (CircleCI, `.circleci/config.yml`)
Git-flow branches (`master`, `develop`, `feature/*`, `release/*`, `hotfix/*`). Both deploy jobs reuse the build job's workspace (which already contains the AAR), run `./gradlew publish`, zip `build/staging-deploy/com`, and POST it with `MAVEN_CENTRAL_TOKEN`:
- `master` → `deploy_to_maven_central` → Central Publisher API (`/api/v1/publisher/upload`).
- `release/*`, `hotfix/*` → `deploy` → POSTs the same bundle to `https://central.sonatype.com/repository/maven-snapshots`. curl runs without `--fail`, so a rejected upload doesn't fail the job. Check the response in the log.

CI quirks: in the workflow, the `build` job's `filters:` is indented as a sibling of `build:` instead of being nested under it. The curl calls use `--verbose`, which echoes the `Authorization` header into the CI log. The `centralManualTestingAuthHeaderValue` line that `deploy` appends to `gradle.properties` is not read by any build script.

## Architecture

**Tags → JUL loggers (`LogManager.kt`).** Users declare nested Kotlin `object`s extending `Tag`:

```kotlin
object LogTag : Tag() { object Net : Tag() { object Http : Tag() } }   // names: LogTag, LogTag.Net, LogTag.Net.Http
```

A tag's name is derived reflectively from `javaClass.name`: the outer class is found by splitting on `$` and instantiated via `kotlin.objectInstance` or a no-arg constructor. Because names are dot-joined, **JUL's own parent/child logger hierarchy applies**. A level or handler set on `LogManager.getLogger(LogTag)` covers every nested tag, which is how the samples wire everything (one handler on the root tag). Overriding `Tag.name` renames only that tag's logger. Children still build their names from the parent's class-derived name, so they no longer sit under the renamed logger. `consumer-rules.pro` is empty, so minified consumers get obfuscated tag names unless they keep their `Tag` classes.

**`L` facade.** `L.debug/info/notice/warning/error/asserts(tag, msg, throwable?, depth)` appends `(File.kt:line)` from `Exception().stackTrace[depth + 2]`. It builds that stack trace and the message **on every call, even when the level is disabled**. Callers on hot paths should check the level first; `algorigoble2` has its own `LazyLog` wrapper for this. Use `depth` when wrapping `L` so that the reported line is the caller's.

**Level mapping is shifted by one** relative to JUL names: `Level.INFO` = JUL `CONFIG`, `Level.NOTICE` = JUL `INFO`, `DEBUG` = `FINE`, `VERBOSE` = `FINEST`, `ASSERTS` = `OFF`. Android installs its own `AndroidHandler` on the JUL root logger that prints JUL `INFO`+, so without `LogManager.removeRootAndroidHandler()` every NOTICE+ record appears twice in logcat.

**`LogDelegate`** (`LogManager.addDelegate`, `initTags`) is a hook that is told about every tag name. Only `DataDogLogDelegate` uses it. Delegates learn about a tag only through `initTags(...)` (recursive over `getChildren()`), or through `addDelegate` for tags already in the map. `L.*` / `getLogger` on a brand-new tag do **not** notify delegates.

### Handlers (`handler/`)
- `AlgorigoLogHandler` — a fan-out handler. Sub-handlers receive a record only if their own level allows it. `removeHandler` also closes the removed handler.
- `AndroidHandler` — a copy of Android's internal handler. It shortens logger names to ≤23-char logcat tags and ignores any formatter you pass.
- `RotatingFileHandler` — size-based rotation, checked after each publish. `rotateCheckIntervalMillis` is a *minimum gap between rotations*, not a time-based trigger. Rotated files are `<base><yyyy-MM-dd-HH-mm-ss UTC><postfix>`. On start, it adopts **any** file in the base file's directory whose name contains such a timestamp, so give each handler its own directory. `registerS3Uploader(...)` uploads rotated files without a postfix (including leftovers from previous runs, via `rotatedFileObservable`'s `startWith`), retries every minute, and then renames the file with postfix `s3`. `KeyFormat` builds S3 keys from patterns where `@...@` segments are `SimpleDateFormat` patterns.
- `CloudWatchHandler` — resolves the group/stream names (`Single`s), creates the group (with a retention policy) and the stream if they are missing, and retries init every minute on `Schedulers.io()`. With `useQueue = true` (default), records go to `util/LogUploadStream` backed by SQLite (`util/LogDatabase`, a **fixed DB name `android_cloudwatch_log.db`** shared by every CloudWatch handler in the process). A batch is sent when it reaches `maxBatchCount` or `maxQueueSize` bytes, or after `sendIntervalMillis` (minimum 10 s) without a batch. Rows are deleted by `send_index` after a successful `PutLogEvents`. A failed batch keeps its nonzero `send_index`, and selection only picks `send_index = 0`, so a failed batch is not retried in the same process. When the DB opens, it resets all `send_index` to 0 (so unacknowledged batches are re-sent) and prunes rows older than the retention period.
- `DataDogHandler` — forwards to the per-tag Datadog `Logger` created by `DataDogLogDelegate.initTag`. Its constructor throws `IllegalStateException` unless Datadog is initialized **and** a `DataDogLogDelegate` was added to `LogManager` first. Records for tags the delegate never saw are silently dropped (`getLogger` returns null). `DataDogLogDelegate` initializes the Datadog SDK only if it isn't already initialized, and its `remoteLevel`, sample rate, and other options are applied only in that case.

`CredentialsProviderHolder` wraps AWS credentials for the S3 and CloudWatch paths: `AccessKeyProvider` (static key pair) or `IdentityPoolProvider` (Cognito identity pool).

### Known bugs worth knowing before touching the CloudWatch path
- `LogDatabase.onOpen` computes `retentionDays * 24 * 60 * 60 * 1000` in `Int` arithmetic, which overflows for retention ≥ 25 days. With the default `RetentionDays.month6`, the cutoff lands in the future, and every queued row is deleted when the DB opens.
- Both delivery paths buffer through unbounded `ReplayRelay`s (`CloudWatchHandler.logRelay` when `useQueue = false`, and `LogUploadStream.eventRelay`), which retain every record for the handler's lifetime.

## Conventions

- Sample apps keep AWS keys, the Cognito identity pool ID, and the Datadog client token as empty-string constants. Supply them locally and never commit real values.
- Commit messages are in Korean.
