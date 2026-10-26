"""Package a reviewed Echo Git ref and its exact runtime dependency sources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile

from generate_notices import local_path


REPOSITORY_SOURCES = {
    "com.github.teamnewpipe:NewPipeExtractor:v0.26.5": (
        "NewPipeExtractor-v0.26.5-source.zip",
        "https://codeload.github.com/TeamNewPipe/NewPipeExtractor/zip/refs/tags/v0.26.5",
        "59a67ac0391985f2881f9dff6c4973b9ac776b8fe9ae52eab667354c6019b293"),
    "com.android.tools:desugar_jdk_libs_nio:2.1.5": (
        "desugar_jdk_libs-2.1.5-source.zip",
        "https://codeload.github.com/google/desugar_jdk_libs/zip/73170c345e6a762fc6a1f0301bb15218850023ef",
        "cf0b48cb046b6517ed8e2a816c125116a907e13e48e341882ba6f4016a155d9f"),
    "com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5": (
        "r8-desugar-config-2.1.5-source.tar.gz",
        "https://r8.googlesource.com/r8/+archive/c331a820ddae7dea41f41a04375b784486d0f705.tar.gz",
        "ae4a15707690552756886dcf1f9a4def079fdb09359897055c0b5dc8b2457a32"),
}


def runtime_classes(data, extension):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if extension == ".jar":
            return any(name.endswith(".class") for name in archive.namelist())
        return any(runtime_classes(archive.read(name), ".jar") for name in archive.namelist()
                   if name == "classes.jar" or name.startswith("libs/") and name.endswith(".jar"))


def git(*args):
    return subprocess.check_output(["git", *args])


def package(ref, runtime_inputs, source_inputs, output):
    if git("status", "--porcelain").strip():
        raise ValueError("Commit the reviewed source and notices before packaging a release")
    commit = git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
    inventory_path = "licenses/runtime/dependency-inventory.json"
    inventory = git("show", commit + ":" + inventory_path)
    if json.loads(inventory) != json.loads(Path(inventory_path).read_bytes()):
        raise ValueError("The source ref and inspected runtime inventory do not match")
    components = json.loads(inventory)["components"]
    artifacts = {":".join(item[key] for key in ("group", "module", "version")): local_path(item["file"])
                 for item in json.loads(runtime_inputs.read_text(encoding="utf-8-sig"))}
    sources = {item["coordinate"]: item for item in json.loads((source_inputs / "downloaded-sources.json").read_text())}
    selected = []
    for component in components:
        coordinate = component["coordinate"]
        artifact = artifacts[coordinate]
        data = artifact.read_bytes()
        if hashlib.sha256(data).hexdigest() != component["sha256"]:
            raise ValueError(f"Inspected artifact changed: {coordinate}")
        if coordinate in REPOSITORY_SOURCES:
            filename, url, expected_hash = REPOSITORY_SOURCES[coordinate]
            source = {"file": filename, "url": url, "sha256": expected_hash}
        else:
            source = sources.get(coordinate, {})
        if "file" not in source:
            if runtime_classes(data, artifact.suffix):
                raise ValueError(f"Corresponding source missing for runtime classes: {coordinate}")
            selected.append({"coordinate": coordinate, "note": "No runtime classes: empty placeholder or build-only metadata/analyzer"})
            continue
        archive = source_inputs / source["file"]
        content = archive.read_bytes()
        if archive.name.endswith(".tar.gz"):
            with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as check:
                if not check.getmembers():
                    raise ValueError(f"Empty source archive: {archive.name}")
        else:
            with zipfile.ZipFile(io.BytesIO(content)) as check:
                if check.testzip() is not None:
                    raise ValueError(f"Corrupt source archive: {archive.name}")
        digest = hashlib.sha256(content).hexdigest()
        if source.get("sha256", digest) != digest:
            raise ValueError(f"Source archive changed: {archive.name}")
        selected.append({"coordinate": coordinate, "file": archive.name, "url": source["url"], "sha256": digest})
    source_zip = git("archive", "--format=zip", "--prefix=EchoMusic-source/", commit)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_suffix(output.suffix + ".part")
    staging.write_bytes(source_zip)
    with zipfile.ZipFile(staging, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for source in selected:
            if "file" in source:
                archive.write(source_inputs / source["file"], "EchoMusic-source/third-party-sources/" + source["file"])
        manifest = {"echoCommit": commit, "runtimeInventorySha256": hashlib.sha256(inventory).hexdigest(), "sources": selected}
        archive.writestr("EchoMusic-source/third-party-sources/SOURCE_MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
        archive.writestr("EchoMusic-source/third-party-sources/README.txt",
            "These are unmodified dependency source archives matching the inspected Echo build.\n"
            "Original copyright notices and license terms remain in each archive.\n"
            "SOURCE_MANIFEST.json records Maven identities, source locations and archive SHA-256.\n"
            "NewPipeExtractor and the desugared Java runtime include full repository build sources.\n"
            "Build Echo from the root README and Gradle wrapper; third-party upstream build files\n"
            "and source archives allow modifying/rebuilding their components independently.\n"
            "No private signing key, credentials, device audio, or workspace caches are included.\n")
    staging.replace(output)
    print(f"Corresponding source: {output} ({output.stat().st_size} bytes), commit {commit}")
    print("SHA-256:", hashlib.sha256(output.read_bytes()).hexdigest())


def self_test():
    for entries, expected in [({"META-INF/MANIFEST.MF": "empty"}, False), ({"pkg/Test.class": "bytecode"}, True)]:
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        assert runtime_classes(data.getvalue(), ".jar") == expected
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, "w") as archive:
            archive.writestr("classes.jar", data.getvalue())
            archive.writestr("lint.jar", b"build-only analyzer ignored")
        assert runtime_classes(aar.getvalue(), ".aar") == expected
    print("Source packager self-check passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref")
    parser.add_argument("--runtime-inputs", type=Path, default=Path("build/runtime-license-inputs.json"))
    parser.add_argument("--source-inputs", type=Path, default=Path("build/release-source-inputs"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not args.ref or not args.output:
            parser.error("--ref and --output are required")
        package(args.ref, args.runtime_inputs, args.source_inputs, args.output)
