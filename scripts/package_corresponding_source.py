"""Package a reviewed Echo Git ref and its exact runtime dependency sources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile

from generate_notices import local_path


REPOSITORY_SOURCES = {
    # AndroidX 1.1.7 shades protobuf-javalite 4.28.2; its Maven sources classifier is empty.
    "androidx.datastore:datastore-preferences-external-protobuf:1.1.7": (
        "protobuf-v28.2-source.zip",
        "https://codeload.github.com/protocolbuffers/protobuf/zip/refs/tags/v28.2",
        "0b7ef3506fc40ad840add138d37d443c4a9f5b0b307c25d516dbbef4dc0bc5ca"),
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

# Exact release snapshot linked by Android's official DataStore 1.1.7 release notes.
DATASTORE_REPACKAGING_SOURCES = [
    ("androidx-datastore-external-protobuf-420da5f-source.tar.gz",
     "https://android.googlesource.com/platform/frameworks/support/+archive/420da5f291449f11801e378c6789a328a06ec6cb/datastore/datastore-preferences-external-protobuf.tar.gz",
     "5c96e80bcb2c110d302b83d2293c974e68d46c73c97ddce97bea5a85b6a16519"),
    ("androidx-repackaging-buildsrc-420da5f-source.tar.gz",
     "https://android.googlesource.com/platform/frameworks/support/+archive/420da5f291449f11801e378c6789a328a06ec6cb/buildSrc/private/src/main/kotlin/androidx/build.tar.gz",
     "8e9c90072a15444bef4d597651698a87abea6436ec915438e8e0a8326f1392df"),
    ("androidx-repackaging-gradle-420da5f-source.tar.gz",
     "https://android.googlesource.com/platform/frameworks/support/+archive/420da5f291449f11801e378c6789a328a06ec6cb/gradle.tar.gz",
     "680bffdb0c62ea7cb29af2f5b7f94416f16e36bae5451fdb4662a5f0bcead681"),
]


def runtime_classes(data, extension):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if extension == ".jar":
            return any(name.endswith((".class", ".dll", ".so", ".dylib", ".jnilib", ".exe")) for name in archive.namelist())
        return any(runtime_classes(archive.read(name), ".jar") for name in archive.namelist()
                   if name == "classes.jar" or name.startswith("libs/") and name.endswith(".jar"))


def native_runtime(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return any(name.endswith((".dll", ".so", ".dylib", ".jnilib", ".exe")) for name in archive.namelist())


def git(*args):
    return subprocess.check_output(["git", *args])


def checked_archive(path, expected_hash, required_suffixes=()):
    content = path.read_bytes()
    if path.name.endswith(".tar.gz"):
        with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as check:
            names = [entry.name for entry in check.getmembers() if entry.isfile()]
            if not names:
                raise ValueError(f"Empty source archive: {path.name}")
    else:
        with zipfile.ZipFile(io.BytesIO(content)) as check:
            names = [entry.filename for entry in check.infolist() if not entry.is_dir()]
            if check.testzip() is not None or not names:
                raise ValueError(f"Corrupt or empty source archive: {path.name}")
    if required_suffixes and not any(name.endswith(required_suffixes) for name in names):
        raise ValueError(f"Source archive is a placeholder or lacks required source code: {path.name}")
    digest = hashlib.sha256(content).hexdigest()
    if expected_hash != digest:
        raise ValueError(f"Source archive changed: {path.name}")
    return digest


def source_name(value):
    if not value or Path(value).name != value or "/" in value or "\\" in value:
        raise ValueError("Source archive must use a plain filename")
    return value


def package(ref, runtime_inputs, source_inputs, output, desktop_runtime_inputs=None):
    if git("status", "--porcelain").strip():
        raise ValueError("Commit the reviewed source and notices before packaging a release")
    commit = git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
    inventories = [("licenses/runtime/dependency-inventory.json", runtime_inputs)]
    if desktop_runtime_inputs is not None:
        inventories.append(("licenses/desktop-runtime/dependency-inventory.json", desktop_runtime_inputs))
    components, artifacts, inventory_hashes = {}, {}, {}
    for inventory_path, inputs in inventories:
        inventory = git("show", commit + ":" + inventory_path)
        if json.loads(inventory) != json.loads(Path(inventory_path).read_bytes()):
            raise ValueError("The source ref and inspected runtime inventory do not match")
        inventory_hashes[inventory_path] = hashlib.sha256(inventory).hexdigest()
        rows = {":".join(item[key] for key in ("group", "module", "version")): local_path(item["file"])
                for item in json.loads(inputs.read_text(encoding="utf-8-sig"))}
        for component in json.loads(inventory)["components"]:
            coordinate = component["coordinate"]
            if coordinate in components and components[coordinate]["sha256"] != component["sha256"]:
                raise ValueError(f"Conflicting runtime artifacts for {coordinate}")
            components[coordinate] = component
            artifacts[coordinate] = rows[coordinate]
    sources = {item["coordinate"]: item for item in json.loads((source_inputs / "downloaded-sources.json").read_text())}
    selected = []
    for component in components.values():
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
                raise ValueError(f"Corresponding source missing for runtime code: {coordinate}")
            selected.append({"coordinate": coordinate, "note": "No runtime classes: empty placeholder or build-only metadata/analyzer"})
            continue
        archive = source_inputs / source_name(source["file"])
        required = (".c", ".h", ".cpp", ".cc", ".m", ".mm", ".S") if artifact.suffix == ".jar" and native_runtime(data) else (
            ".java", ".kt", ".scala", ".c", ".h", ".cpp", ".cc", ".m", ".mm", ".S") if runtime_classes(data, artifact.suffix) else ()
        digest = checked_archive(archive, source.get("sha256") or hashlib.sha256(archive.read_bytes()).hexdigest(), required)
        selected.append({"coordinate": coordinate, "file": archive.name, "url": source["url"], "sha256": digest})
        if coordinate == "androidx.datastore:datastore-preferences-external-protobuf:1.1.7":
            for filename, url, expected_hash in DATASTORE_REPACKAGING_SOURCES:
                checked_archive(source_inputs / filename, expected_hash)
                selected.append({"coordinate": coordinate + ":build-recipe", "file": filename, "url": url, "sha256": expected_hash})
    if desktop_runtime_inputs is not None:
        java_path = "licenses/desktop-runtime/bundled-java.json"
        java_bytes = git("show", commit + ":" + java_path)
        if json.loads(java_bytes) != json.loads(Path(java_path).read_bytes()):
            raise ValueError("The source ref and bundled Java runtime metadata do not match")
        java = json.loads(java_bytes)
        if java.get("format") != "echo-bundled-java" or java.get("version") != 1:
            raise ValueError("Unsupported bundled Java runtime metadata")
        source = java["source"]
        archive = source_inputs / source_name(source["file"])
        if not source["url"].startswith("https://"):
            raise ValueError("Bundled Java source needs an HTTPS origin")
        checked_archive(archive, source["sha256"])
        selected.append({key: source[key] for key in ("coordinate", "file", "url", "sha256")})
        inventory_hashes[java_path] = hashlib.sha256(java_bytes).hexdigest()
        extra_path = "licenses/desktop-runtime/extra-source-inputs.json"
        extra_bytes = git("show", commit + ":" + extra_path)
        if json.loads(extra_bytes) != json.loads(Path(extra_path).read_bytes()):
            raise ValueError("The source ref and additional native source metadata do not match")
        for source in json.loads(extra_bytes):
            archive = source_inputs / source_name(source["file"])
            if not source["url"].startswith("https://"):
                raise ValueError("Additional native source needs an HTTPS origin")
            checked_archive(archive, source["sha256"])
            selected.append({key: source[key] for key in ("coordinate", "file", "url", "sha256")})
        inventory_hashes[extra_path] = hashlib.sha256(extra_bytes).hexdigest()
    source_zip = git("archive", "--format=zip", "--prefix=EchoMusic-source/", commit)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.with_suffix(output.suffix + ".part")
    staging.write_bytes(source_zip)
    with zipfile.ZipFile(staging, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        included = set()
        for source in selected:
            if "file" in source:
                if source["file"] in included:
                    continue
                archive.write(source_inputs / source["file"], "EchoMusic-source/third-party-sources/" + source["file"])
                included.add(source["file"])
        manifest = {"echoCommit": commit, "runtimeInventorySha256": inventory_hashes["licenses/runtime/dependency-inventory.json"],
                    "inventories": inventory_hashes, "sources": selected}
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
    native = io.BytesIO()
    with zipfile.ZipFile(native, "w") as archive:
        archive.writestr("org/example/runtime.dll", "native code")
    assert runtime_classes(native.getvalue(), ".jar")
    assert native_runtime(native.getvalue())
    with tempfile.TemporaryDirectory() as directory:
        placeholder = Path(directory) / "placeholder-sources.jar"
        with zipfile.ZipFile(placeholder, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "placeholder")
        digest = hashlib.sha256(placeholder.read_bytes()).hexdigest()
        assert checked_archive(placeholder, digest) == digest
        try:
            checked_archive(placeholder, digest, (".java", ".cpp"))
        except ValueError:
            pass
        else:
            raise AssertionError("Accepted placeholder source for runtime code")
    for name in ("../source.zip", "nested/source.zip", "nested\\source.zip", ""):
        try:
            source_name(name)
        except ValueError:
            pass
        else:
            raise AssertionError("Accepted an unsafe source archive name")
    print("Source packager self-check passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref")
    parser.add_argument("--runtime-inputs", type=Path, default=Path("build/runtime-license-inputs.json"))
    parser.add_argument("--source-inputs", type=Path, default=Path("build/release-source-inputs"))
    parser.add_argument("--desktop-runtime-inputs", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not args.ref or not args.output:
            parser.error("--ref and --output are required")
        package(args.ref, args.runtime_inputs, args.source_inputs, args.output, args.desktop_runtime_inputs)
