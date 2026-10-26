# Echo Music licenses and corresponding source

Echo Music's original source is available under **GPL-3.0-or-later**, as authorized by the project owner on 2026-10-26. See [LICENSE](LICENSE) for the grant and [COPYING](COPYING) for the full GPL version 3 text. Third-party components retain their original copyrights and license terms; Echo's grant does not relicense those components or third-party music and artwork.

The `licenses/` directory is bundled into the APK as assets. It contains full license texts and original notices. The generated `licenses/runtime/dependency-inventory.json` lists the exact resolved Maven coordinates, versions, artifact SHA-256 values and debug/release/desugaring configuration membership. `DEPENDENCIES.txt` provides a readable list; `ARTIFACT_NOTICES.txt` retains legal notices found in the actual upstream JARs/AARs, including nested `classes.jar` files. Those notices are copied without replacing their copyright holders or terms.

Important bundled components include:

| Component | Original license | Source and notices |
|---|---|---|
| NewPipeExtractor v0.26.5, unmodified | GPL-3.0-or-later | [Pinned upstream source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5); [GPL text](licenses/NewPipeExtractor-GPL-3.0.txt); [upstream notice](licenses/NOTICE-NewPipeExtractor.txt) |
| Rhino / Rhino engine 1.8.1 | MPL-2.0 and the additional terms identified by upstream | [Release source](https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release); [complete upstream license file](licenses/Rhino-LICENSE.txt) |
| Desugared Java library 2.1.5 | GPL-2.0 with the Classpath Exception; additional upstream notices | [Pinned release-preparation source](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef); [license](licenses/Desugar-LICENSE.txt), [assembly exception](licenses/Desugar-ASSEMBLY_EXCEPTION.txt), [additional information](licenses/Desugar-ADDITIONAL_LICENSE_INFO.txt) |
| Protocol Buffers | BSD-3-Clause | [Upstream license and copyright](licenses/Protobuf-LICENSE.txt); exact artifacts in the runtime inventory |
| nanojson, pinned NewPipe fork | Apache-2.0 | [Exact source commit](https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996); [actual source copyright header](licenses/Nanojson-NOTICE.txt) |
| AndroidX, Kotlin, coroutines, Media3, Hilt, OkHttp, Okio, Coil and other runtime dependencies | Their individual declared licenses, primarily Apache-2.0 | See the full runtime inventory, original artifact notices and [Apache-2.0 text](licenses/Apache-2.0.txt) |

MIT/BSD notices from individual artifacts remain in `ARTIFACT_NOTICES.txt`; they are not replaced with a generic copyright statement. The desugaring Classpath Exception is retained with its original text. Third-party source headers and build files remain intact in the corresponding-source archives.

## Release source

An APK release must provide matching Echo source and the bundled dependency source described by the release's source manifest. The release source bundle must be generated from the same reviewed Git commit/tag as the APK, with the complete four-module sources, schemas, Gradle wrapper/build files, license assets and dependency source archives. Release notes must link directly to that bundle beside the APK. Downloading source does not require credentials, and recipients may rebuild and install their modified versions using their own signing key. Echo's private signing key is never included.

Do not recursively archive the working directory: Gradle caches, build outputs, app/account dumps, local configuration, captures and signing material are excluded. A GitHub automatic source archive alone does not include separately bundled dependency source. Preserve the matching source bundle for as long as the APK remains available.

## Regenerating the inventory

After changing runtime dependencies, resolve the actual build configurations and regenerate the notices before building the distribution APK:

```powershell
.\gradlew.bat -I scripts/runtime-license-inputs.init.gradle :app:writeRuntimeLicenseInputs
python scripts/generate_notices.py --self-test
python scripts/generate_notices.py --inputs build/runtime-license-inputs.json --output licenses/runtime
```

The generator fails on undeclared licenses and on proprietary Google identity dependencies rather than silently treating them as GPL-compatible. nanojson's missing POM license is resolved explicitly from its pinned upstream source header. Dependency versions, source availability, applicable notices and the final packaged assets must be checked again for each release; an inventory is evidence of the inspected build, not permission to omit upstream conditions.

For the approved 0.1.8/code9 release, the reviewed source inputs are staged privately under ignored `build/release-source-inputs/`. After the complete source, notices and inventory are reviewed and committed, the source packager checks the matching committed inventory and artifact/source hashes before creating the distribution bundle:

```powershell
python scripts/package_corresponding_source.py --self-test
python scripts/package_corresponding_source.py --ref v0.1.8 --output build/releases/EchoMusic-9-source.zip
```

The packager requires a clean reviewed checkout and a prepared `downloaded-sources.json` source-input manifest. It excludes sources for artifacts verified to contain no runtime classes (empty compatibility placeholders or build-only metadata). It rejects missing source for any actual runtime class. The output's `third-party-sources/SOURCE_MANIFEST.json` records exact source URLs and SHA-256 values, including full repository sources for NewPipeExtractor, the desugared Java library and its R8 configuration. Those source archives are distribution inputs, not APK assets. Keep the prepared source inputs outside Git, and publish the resulting matching bundle alongside the APK.
