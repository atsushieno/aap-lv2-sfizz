# aap-lv2-sfizz: sfizz plugin port to AAP using aap-lv2

This repository is an example port of sfizz LV2 plugin to [Audio Plugins For Android](https://github.com/atsushieno/aap-core), using [aap-lv2](https://github.com/atsushieno/aap-lv2).

## SFZ instruments

The native Compose UI includes an expandable SFZ selector alongside the plugin parameters, rendered as a collapsible directory tree. It lists three kinds of sources:

- the **bundled** Splendid Grand Piano,
- instruments published by **SFZ resource providers** — other installed apps that expose immutable instrument packs, and
- **SFZ folders** — document trees you pick from local storage.

Selecting an entry loads it into the running sfizz instance without restarting the plugin; expect a brief mute while the instrument changes. The selection is persisted by stable identity (provider component + pack id + revision, or the folder tree URI) and restored on process restart. If the provider or folder is no longer available, the instrument is reported as unavailable rather than silently substituted.

### SFZ folders

To add local instruments, expand the selector, open **SFZ folders**, and choose **Add folder**. Select a folder containing both the SFZ files and their samples. Android grants persistent read access to that tree; on recent Android versions select a subfolder of Downloads, not the Downloads root itself.

Folder discovery indexes relative paths only. SFZ includes and samples are opened individually through the document provider when sfizz requests them; unrelated files are not read or packaged. Local files are mapped directly and their descriptors closed after mapping. A provider returning a non-seekable stream uses a temporary cache for that requested file only. Relative paths may traverse parents within the selected tree, but cannot escape it. Do not modify or truncate sample files while an instrument is using them; select the instrument again to reload changes.

### SFZ resource providers

A *provider* is a normal Android app that exposes one or more immutable SFZ packs to AAP Sfizz (and to any other host that speaks the same contract) over a bound service. AAP Sfizz discovers providers by the intent action `org.androidaudioplugin.SfzResourceService.V1` and binds to each matching component; a host targeting Android 11+ must declare that action under `<queries>` in its own manifest.

The `:sfz-provider` library module in this repository defines the contract:

- AIDL `ISfzResourceService` — `getProtocolVersion()`, `listInstruments(offset, limit)` (paginated `Bundle`s carrying `id`, `label`, `revision`, and an optional asset-relative `path` used for tree display), and `openInstrument(instrumentId, revision)`.
- AIDL `ISfzResourceSession` — `getEntryPath()`, `listResources(offset, limit)`, `openResource(name)` returning a read-only `AssetFileDescriptor` (start offset plus finite length; an APK asset is a sub-range of the APK file), and `close()`.
- `AssetSfzResourceService` — an abstract `Service` that implements the whole contract from a list of `Instrument(id, label, revision, assetRoot, entry)` values backed by APK assets. Subclass it, override `instruments`, and register it with the `SfzResourceService.V1` intent filter.

Provider requirements:

- Sample and SFZ files must be stored **uncompressed** so `AssetManager.openFd()` can hand out a mappable descriptor. Configure `androidResources { noCompress 'sfz', 'wav', 'flac', 'ogg', 'mp3' }` (see `sample-sfz-provider/build.gradle`).
- A pack is **immutable**. Bump `revision` whenever any resource bytes or names change; hosts pin a selection to `(instrumentId, revision)`.
- `assetRoot` is the entire sharing boundary — everything under it (including samples shared between instruments) is listable and openable, and nothing outside it is. Do not point it at an asset directory that also holds confidential data.

`sample-sfz-provider` is a minimal working example: one instrument (`pack/instruments/main.sfz`) with a nested `#include` and a WAV sample. AAP Sfizz itself publishes the bundled piano through the same contract via `BundledSfzResourceService`, so the bundled and add-on paths are identical.

Inside the plugin process, `SfzResourceClient` performs the IPC off the UI/audio thread, collects the pack's descriptors into a `Snapshot`, and passes them to native code (`native/AudioPluginLV2Resources_jni.cpp`, library `aap-lv2-sfizz-resources`). The sfizz build is patched to accept a resource-resolver feature (`urn:org.androidaudioplugin:sfz-resources:1`); resource bytes are mapped from the descriptor ranges rather than copied wholesale, and no provider IPC happens on the audio callback. The `SfzResourceService.V1` action, its AIDL, and the pack namespace rules are still provisional and may change before a frozen public contract.

## Building

See our [GitHub Actions script](.github/workflows/actions.yml) for the normative setup.

For local desktop development, prepare sfizz, build the local aap-lv2 submodule into Maven Local, then build this app:

```
$ scripts/prepare-sources.sh
$ cd external/aap-lv2/external/aap-core
$ ./gradlew publishToMavenLocal
$ cd ../..
$ ./gradlew :androidaudioplugin-lv2:build :androidaudioplugin-lv2:publishToMavenLocal
$ cd ../..
$ ./gradlew build bundle
```

## Hacking

The default bundled instrument is configured at build time; external instruments can be selected at runtime using the native UI above.

Our `aap_metadata.xml` is generated by `aap-import-lv2-metadata` tool but it is done manually. So every time sfizz submodule is updated, chances are that the metadata needs to be regenerated or manually fixed:

```
$ cd external/aap-lv2
$ cmake -E rm -rf tools/aap-import-lv2-metadata/build
$ cmake -S tools/aap-import-lv2-metadata -B tools/aap-import-lv2-metadata/build -G Ninja -DCMAKE_BUILD_TYPE=Debug
$ cmake --build tools/aap-import-lv2-metadata/build
$ cd ../..
$ external/aap-lv2/tools/aap-import-lv2-metadata/build/aap-import-lv2-metadata app/src/main/assets/lv2 app/src/main/res/xml
```

Note that the resources may be relocated; they sometimes do.

`app` directory directly references sfizz's own `CMakeLists.txt` from `build.gradle`. Though note that sfizz itself is patched during source preparation.

## Licensing notice

`aap-lv2-sfizz` itself is distributed under the MIT license.

LV2 (repository for the headers) is under the ISC license.

`sfizz` is distributed under the MIT license, but it has dependencies that are under the Apache License 2.0 etc. See [the project repository](https://github.com/sfztools/sfizz) for details. As of v0.1.6, AAP Sfizz does not depend on libsndfile anymore.

`AAP Sfizz` apk bundles sample external sfz from [sfzinstruments/SplendidGrandPiano](https://github.com/sfzinstruments/SplendidGrandPiano) whose samples are Public Domain.

The entire plugin application bundles `androidaudioplugin-lv2` AAR module from `aap-lv2`, and `androidaudioplugin` AAR module, and is packaged into one application.
