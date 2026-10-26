"""Inventory resolved runtime artifacts and retain their embedded legal notices.

First run Gradle with runtime-license-inputs.init.gradle. This script reads only
those resolved artifacts; it does not infer the runtime graph from cache contents.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

NANOJSON = "com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"


def local_path(value):
    path = Path(value).absolute()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path


def pom_metadata(path):
    root = ET.parse(path).getroot()
    for element in root.iter():
        element.tag = element.tag.rsplit("}", 1)[-1]
    licenses = [{"name": item.findtext("name", "").strip(), "url": item.findtext("url", "").strip()}
                for item in root.findall("licenses/license")]
    source = (root.findtext("scm/url", "") or root.findtext("url", "")).strip()
    if not licenses and isinstance(path, Path) and root.find("parent") is not None:
        parent = root.find("parent")
        gav = [parent.findtext(key, "") for key in ("groupId", "artifactId", "version")]
        candidates = list(path.parents[4].joinpath(*gav).rglob("*.pom"))
        if len(candidates) == 1:
            licenses, inherited_source = pom_metadata(candidates[0])
            source = source or inherited_source
    return licenses, source


def embedded_notices(data, prefix="", nested=True):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in sorted(archive.infolist(), key=lambda item: item.filename):
            name = entry.filename.rsplit("/", 1)[-1]
            if entry.is_dir():
                continue
            if re.match(r"(?i)^(licen[cs]e|notice|copying|copyright|third[-_]party[-_]notices?)([.\-_].*|$)", name):
                if entry.file_size > 2_000_000:
                    raise ValueError(f"Legal notice unexpectedly large: {prefix}{entry.filename}")
                result.append((prefix + entry.filename, archive.read(entry).decode("utf-8", errors="replace")))
            elif nested and entry.filename.endswith(".jar"):
                result.extend(embedded_notices(archive.read(entry), prefix + entry.filename + "!", False))
    return result


def generate(inputs, output):
    rows = json.loads(inputs.read_text(encoding="utf-8-sig"))
    components = {}
    notices = []
    for row in sorted(rows, key=lambda item: (item["group"], item["module"], item["version"], item["configuration"])):
        coordinate = ":".join(row[key] for key in ("group", "module", "version"))
        if coordinate in components:
            components[coordinate]["configurations"].append(row["configuration"])
            continue
        artifact = local_path(row["file"])
        poms = list(artifact.parent.parent.rglob("*.pom"))
        if len(poms) != 1:
            raise ValueError(f"Expected one cached POM for {coordinate}, found {len(poms)}")
        licenses, source = pom_metadata(poms[0])
        if coordinate == NANOJSON:
            # The fork POM omits its license; the exact pinned source header supplies it.
            licenses = [{"name": "Apache-2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0.txt"}]
            source = "https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"
        if coordinate == "com.google.protobuf:protobuf-javalite:4.35.1":
            licenses = [{"name": "BSD-3-Clause", "url": "https://github.com/protocolbuffers/protobuf/blob/v35.1/LICENSE"}]
            source = "https://github.com/protocolbuffers/protobuf/tree/v35.1"
        if not licenses:
            raise ValueError(f"Missing declared license for {coordinate}; review its upstream license explicitly")
        if row["group"].startswith("com.google.android.gms") or row["group"].startswith("com.google.android.libraries.identity") or any("Software Development Kit" in item["name"] for item in licenses):
            raise ValueError(f"Proprietary Google identity dependency remains in runtime graph: {coordinate}")
        raw = artifact.read_bytes()
        originals = embedded_notices(raw)
        components[coordinate] = {
            "coordinate": coordinate, "configurations": [row["configuration"]],
            "artifact": artifact.name, "sha256": hashlib.sha256(raw).hexdigest(),
            "licenses": licenses, "source": source,
            "embeddedNoticeEntries": [name for name, _ in originals],
        }
        for name, content in originals:
            notices.append(f"{'=' * 72}\n{coordinate}\nOriginal archive entry: {name}\n{'=' * 72}\n{content.rstrip()}\n")
    output.mkdir(parents=True, exist_ok=True)
    inventory = {"format": "echo-runtime-licenses", "version": 1, "components": list(components.values())}
    (output / "dependency-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    (output / "ARTIFACT_NOTICES.txt").write_text("\n".join(notices), encoding="utf-8")
    summary = ["Echo Music runtime dependency notices", "Third-party components retain their original licenses and copyrights.",
               "Exact artifact hashes and variant membership: dependency-inventory.json.",
               "Original notices embedded in upstream binaries: ARTIFACT_NOTICES.txt.", ""]
    for item in components.values():
        summary.extend([item["coordinate"], "Variants: " + ", ".join(item["configurations"])])
        summary.extend("License: " + license["name"] + " - " + license["url"] for license in item["licenses"])
        summary.extend(["Upstream source: " + (item["source"] or "See the artifact's upstream Maven POM"), ""])
    (output / "DEPENDENCIES.txt").write_text("\n".join(summary), encoding="utf-8")
    unique_licenses = sorted({(license["name"], license["url"]) for row in components.values() for license in row["licenses"]})
    print(f"Inventoried {len(components)} runtime components; retained {len(notices)} embedded legal notices.")
    for name, url in unique_licenses:
        print(f"{name}: {url}")


def self_test():
    source = io.BytesIO()
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("META-INF/LICENSE.txt", "Original license")
        archive.writestr("classes/Foo.class", "not a notice")
    outer = io.BytesIO()
    with zipfile.ZipFile(outer, "w") as archive:
        archive.writestr("classes.jar", source.getvalue())
        archive.writestr("NOTICE", "Original attribution")
    assert embedded_notices(outer.getvalue()) == [("NOTICE", "Original attribution"), ("classes.jar!META-INF/LICENSE.txt", "Original license")]
    pom = io.BytesIO(b'<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>MIT</name><url>https://example.com/LICENSE</url></license></licenses><scm><url>https://example.com/source</url></scm></project>')
    assert pom_metadata(pom) == ([{"name": "MIT", "url": "https://example.com/LICENSE"}], "https://example.com/source")
    assert pom_metadata(io.BytesIO(b'<project><licenses><license><name>Apache-2.0</name></license></licenses></project>'))[0][0]["name"] == "Apache-2.0"
    print("Notice parser self-check passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not args.inputs or not args.output:
            parser.error("--inputs and --output are required")
        generate(args.inputs, args.output)
