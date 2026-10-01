# Building Companion

Use the committed wrapper; no system Gradle installation is required. Runtime source checkouts are read-only integration targets, not build dependencies.

| Component | Pinned baseline |
| --- | --- |
| Gradle | 8.10.2, checksum in `gradle/wrapper/gradle-wrapper.properties`. |
| JDK / JVM target | 17. |
| AGP | 8.5.2. |
| Kotlin / stdlib | 1.9.24. |
| Android compile / target / minimum SDK | 34 / 34 / 24. |
| Android Build Tools | 34.0.0 (AGP baseline). |
| Unit tests | JUnit 4.13.2, Robolectric 4.14.1; SDK 28 fixture tests. |
| P1 additions | Coroutines 1.8.1, Hilt 2.51.1 (kapt), Compose BOM 2024.06.00/compiler 1.5.14, Activity 1.9.0, Lifecycle 2.8.2, JVM JSON codec 20240303. |

Plugin/library/SDK versions are centralized in `gradle/libs.versions.toml`; the wrapper pins Gradle independently. P0 preserves the existing tuple. AGP 8.5 requires JDK 17 and at least Gradle 8.7 and supports API 34. Toolchain modernization and signing migration are separate reviewed changes. [Official AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes).

## Wrapper provenance

The scripts and JAR come from the official Gradle `v8.10.2` tag. Before accepting the JAR, its SHA-256 was compared with the official distribution checksum:

```text
wrapper JAR: 2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046
bin ZIP:     31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26
```

The wrapper verifies the ZIP with `distributionSha256Sum`; CI uses `gradle/actions/setup-gradle` for cache/wrapper validation and invokes `./gradlew`. [Gradle checksums](https://gradle.org/release-checksums/), [wrapper checksum verification](https://docs.gradle.org/8.10.2/userguide/gradle_wrapper.html#sec:verification).

## Linux build and CI

Configure an Android SDK with `platforms;android-34` and `build-tools;34.0.0`, following the official SDK setup and license process. Set `ANDROID_HOME` or use an untracked `local.properties`. Then:

```sh
./gradlew checkArchitecture :core:application:test :protocol:test :adapters:transport:test \
  testDebugUnitTest lintDebug assembleRelease --no-daemon --console=plain
```

CI first builds/checks the pure JVM modules with an invalid SDK path, then runs the default Android test provider and lint on Linux, validates the v2 contracts in an isolated Python environment, and uploads the signed APK artifact. Publishing an APK remains tag-triggered. [Android command-line tools](https://developer.android.com/tools), [Robolectric configuration](https://robolectric.org/configuring/).

For core/transport work on a host without an Android SDK:

```sh
./gradlew -PcoreOnly=true checkArchitecture :core:domain:check :core:application:check \
  :protocol:check :adapters:transport:check --no-daemon --console=plain
python3 -m unittest discover -s tests/architecture -v
```

The [architecture guide](architecture.md) explains the module graph, boundary checks and injected lifecycle state. `testDebugUnitTest` runs Android adapter/UI/composition regressions; JVM application/protocol/socket tests use their separate `test` tasks.

## Termux build profile

Install JDK 17, Python 3.11 or newer and the Android/Termux build of `aapt2`. Google Linux SDK executables target a different host; the Android JVM build uses the native Termux resource compiler instead. The SDK helper downloads two fixed official Google archives, checks pinned SHA-256 digests, rejects unsafe ZIP paths/symlinks and installs into `.build-tools/android-sdk`. It leaves an existing SDK package in place; remove/recreate only this managed cache if its extracted files are damaged.

From this repository:

```sh
pkg install openjdk-17 aapt2 python
python3 tools/prepare_android_sdk.py
ANDROID_HOME="$PWD/.build-tools/android-sdk" \
GRADLE_USER_HOME="$PWD/.build-tools/gradle-home" \
./gradlew checkArchitecture :core:application:test :protocol:test :adapters:transport:test \
  testDebugUnitTest lintDebug assembleRelease --no-daemon --console=plain --max-workers=2 \
  --init-script gradle/termux-tests.init.gradle.kts \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
```

The audited archives are API 34 extension 7 revision 3 and Build Tools 34.0.0. Their SHA-256 pins are in `tools/prepare_android_sdk.py`. During P0 they also matched the SHA-1 checksums in [Google's SDK repository metadata](https://dl.google.com/android/repository/repository2-1.xml): `1f2e9478d6a7601425ceaa553311dc43191f103d` and `d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9`. The metadata is the provenance source; the local SHA-256 pins make future downloads stable.

Robolectric 4.14.1's Conscrypt 2.5.2 OpenJDK dependency lacks a Linux/Android aarch64 JNI library. The explicit init script selects Robolectric's supported `ConscryptMode.OFF`, using its Java BouncyCastle provider. It runs all existing assertions; it does not filter tests or SDKs. These bridge tests exercise intents, notification handles, package-scoped screen/type/scroll behavior and HTTP routing, not TLS. The Linux CI default still exercises its normal provider. This Termux profile does not prove native Conscrypt/TLS behavior. [Robolectric security-provider configuration](https://robolectric.org/configuring/#conscryptmode).

The P0 run produced a release APK and passed all 24 existing tests (5 mail, 5 notification, 14 screen), with zero failures/errors/skips. P1 retains these cases in `:adapters:android` with per-test injected adapters and public routing; business tests invoke application ports with fakes. Runtime/device integration evidence is a separate gate; compiling the APK does not validate model workflows.

## Device contracts

Use the isolated environment and checks described in [the v2 contract](../contracts/device/v2/README.md). Its Python dependencies are pinned in `tools/requirements-contracts.txt`. Termux can build `rpds-py` from source, requiring the existing Rust build tools; Linux CI uses its supported wheels. No dependency is installed into either USIX runtime.

The current public development keystore is retained for sideload upgrade compatibility. P0 does not change package identity, release signing, Android source or device behavior.

## P2 integration and persistence checks

```sh
python3 tools/sync_host_contracts.py --check
python3 -m venv .venv-integration
.venv-integration/bin/pip install -e integration -r integration/requirements-test.txt
.venv-integration/bin/python -m unittest discover -s integration/tests -v
./gradlew -PcoreOnly=true checkArchitecture :core:application:test :protocol:test :adapters:transport:test
.venv-integration/bin/python tools/check_apk_contract_outputs.py
./gradlew :adapters:persistence:testDebugUnitTest testDebugUnitTest lintDebug assembleRelease
```

Room 2.6.1, DataStore 1.1.1 and OkHttp 4.12.0 are pinned compatible additions to the retained Kotlin/SDK baseline, rather than a toolchain upgrade. [Room release](https://developer.android.com/jetpack/androidx/releases/room#2.6.1), [DataStore release](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.1), [OkHttp platform requirements](https://github.com/square/okhttp/tree/parent-4.12.0).

Robolectric's native SQLite runtime is unavailable on this Termux aarch64 host. P2's four real-DB/migration tests remain enabled and mandatory in Linux CI; do not skip them or substitute fake persistence to claim a pass. Local non-SQLite tests, lint and signed APK build can run with the existing Termux provider profile. Physical Android DB and native TLS/WSS evidence remain separate gates. The optional host test dependency `cryptography` creates controlled TLS fixtures and is not installed into either runtime or shipped in the Android app.
