#!/usr/bin/env python3
"""Copy reviewed Java SDK and canonical corpus bytes from git commits and pin every file."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent

SDK_RUNTIME_FILES = [
    "LICENSE",
    "std/lmcode-std.schema.json",
    "src/main/java/io/lmctl/lmcode/Context.java",
    "src/main/java/io/lmctl/lmcode/Execution.java",
    "src/main/java/io/lmctl/lmcode/Formatting.java",
    "src/main/java/io/lmctl/lmcode/Json.java",
    "src/main/java/io/lmctl/lmcode/Op.java",
    "src/main/java/io/lmctl/lmcode/Program.java",
    "src/main/java/io/lmctl/lmcode/Reader.java",
    "src/main/java/io/lmctl/lmcode/Registry.java",
    "src/main/java/io/lmctl/lmcode/Reports.java",
    "src/main/java/io/lmctl/lmcode/ResultJson.java",
    "src/main/java/io/lmctl/lmcode/ResultSink.java",
    "src/main/java/io/lmctl/lmcode/SchemaSignatures.java",
    "src/main/java/io/lmctl/lmcode/StdCollections.java",
    "src/main/java/io/lmctl/lmcode/StdContainers.java",
    "src/main/java/io/lmctl/lmcode/StdFile.java",
    "src/main/java/io/lmctl/lmcode/StdJson.java",
    "src/main/java/io/lmctl/lmcode/StdMath.java",
    "src/main/java/io/lmctl/lmcode/StdReturn.java",
    "src/main/java/io/lmctl/lmcode/StdString.java",
    "src/main/java/io/lmctl/lmcode/StdTesting.java",
    "src/main/java/io/lmctl/lmcode/Stdlib.java",
    "src/main/java/io/lmctl/lmcode/Value.java",
    "src/main/java/io/lmctl/lmcode/Verifier.java",
    "src/main/java/io/lmctl/lmcode/VmException.java",
    "src/main/java/io/lmctl/lmcode/VmString.java",
]


def files(directory: Path):
    return sorted(
        p for p in directory.rglob("*")
        if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc"
    )


def hashes(directory: Path):
    return {
        p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in files(directory)
    }


def extract_git_archive(repo: Path, commit: str, paths: list[str], destination: Path, strip_prefix: str = ""):
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="lmcode-java-sync-") as tmp:
        tar_path = Path(tmp) / "archive.tar"
        with tar_path.open("wb") as out:
            subprocess.run(
                ["git", "-C", str(repo), "archive", "--format=tar", commit, "--", *paths],
                check=True,
                stdout=out,
            )
        extract_dir = Path(tmp) / "extracted"
        extract_dir.mkdir()
        subprocess.run(["tar", "-xf", str(tar_path), "-C", str(extract_dir)], check=True)
        src_root = extract_dir / strip_prefix if strip_prefix else extract_dir
        for path in files(src_root):
            target = destination / path.relative_to(src_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode not in ("sdk", "suite", "check"):
        sys.exit("usage: sync.py sdk|suite [SOURCE_DIR] | check")
    if mode == "check":
        for name, directory in [("sdk", ROOT / "vendor/lmcode-java"), ("suite", ROOT / "sample-suite")]:
            lock = json.loads((ROOT / f"{name}.lock").read_text(encoding="utf-8"))
            if hashes(directory) != lock["sha256"]:
                sys.exit(f"{name} drift: sync reviewed upstream bytes, do not edit vendored files")
        print("PASS SDK and suite locks")
        return

    default_source = ROOT.parent / ("lmcode-java" if mode == "sdk" else "lmcode-cpp")
    source = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else default_source
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if mode == "sdk":
        destination = ROOT / "vendor/lmcode-java"
        extract_git_archive(source, commit, SDK_RUNTIME_FILES, destination)
        repo_name = "lmctl-ai/lmcode-java"
    else:
        destination = ROOT / "sample-suite"
        extract_git_archive(source, commit, ["sample-suite"], destination, strip_prefix="sample-suite")
        repo_name = "lmctl-ai/lmcode-cpp"

    (ROOT / f"{mode}.lock").write_text(
        json.dumps({"repo": repo_name, "commit": commit, "sha256": hashes(destination)}, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Pinned {mode} at {commit}")


if __name__ == "__main__":
    main()
