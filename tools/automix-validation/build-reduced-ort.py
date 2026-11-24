"""Build an experimental CPU-only ORT runtime on Windows/Linux with the official Java JNI."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile

VERSION = "1.29.0"
HERE = Path(__file__).resolve().parent

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path, help=f"Clean ONNX Runtime v{VERSION} Git checkout")
parser.add_argument("official_jar", type=Path)
parser.add_argument("work", type=Path)
parser.add_argument("--jobs", type=int, default=4)
args = parser.parse_args()

source = args.source.resolve()
system = platform.system()
if system not in ("Windows", "Linux") or platform.machine().lower() not in ("amd64", "x86_64"):
    parser.error("Run on native Windows or Linux x86_64")
if subprocess.check_output(["git", "-C", source, "describe", "--tags", "--exact-match"], text=True).strip() != "v" + VERSION:
    parser.error(f"The source checkout must be tagged v{VERSION}")
if args.official_jar.name != f"onnxruntime-{VERSION}.jar":
    parser.error(f"Pass the official onnxruntime-{VERSION}.jar")

work = args.work.resolve()
work.mkdir(parents=True, exist_ok=True)
# Operator reduction rewrites ORT source files, so keep the caller's checkout pristine.
build_source = work / "source"
if build_source.exists():
    parser.error(f"Choose a fresh scratch path. {build_source} already exists")
subprocess.run(["git", "clone", "--local", "--branch", f"v{VERSION}", source, build_source], check=True)
build = work / "build"
subprocess.run([
    sys.executable, str(build_source / "tools/ci_build/build.py"),
    "--build_dir", str(build), "--config", "Release", "--update", "--build",
    "--cmake_generator", "Ninja", "--parallel", str(args.jobs),
    "--build_shared_lib", "--enable_lto",
    "--include_ops_by_config", str(HERE / "required_operators.config"),
    "--skip_tests", "--cmake_extra_defines", "onnxruntime_BUILD_UNIT_TESTS=OFF",
], cwd=build_source, check=True)

target = "win-x64" if system == "Windows" else "linux-x64"
binary = "onnxruntime.dll" if system == "Windows" else "libonnxruntime.so"
entry = f"ai/onnxruntime/native/{target}/{binary}"
candidates = [path for path in (build / "Release").rglob(binary) if path.is_file()]
if len(candidates) != 1:
    raise SystemExit(f"Expected one {binary} in {build / 'Release'}, found {candidates}")
runtime = work / binary
shutil.copyfile(candidates[0], runtime)
if system == "Linux":
    subprocess.run(["strip", "--strip-unneeded", runtime], check=True)
    exports = subprocess.check_output(["nm", "-D", "--defined-only", runtime], text=True)
else:
    exports = subprocess.check_output(["dumpbin", "/exports", runtime], text=True)
if "OrtGetApiBase" not in exports:
    raise SystemExit("Reduced runtime is missing the versioned ORT C API")

output = work / f"onnxruntime-{VERSION}-{target}-reduced.jar"
temporary = output.with_suffix(".part")
with zipfile.ZipFile(args.official_jar) as original, zipfile.ZipFile(
    temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6,
) as result:
    if entry not in original.namelist():
        raise SystemExit(f"{entry} is missing from the official jar")
    for member in original.infolist():
        if member.is_dir():
            continue
        if member.filename.startswith("ai/onnxruntime/native/") and not member.filename.startswith(
            f"ai/onnxruntime/native/{target}/",
        ):
            continue
        if member.filename == entry:
            result.write(runtime, entry)
        else:
            with original.open(member) as incoming, result.open(member.filename, "w") as outgoing:
                shutil.copyfileobj(incoming, outgoing)
os.replace(temporary, output)
print(f"Experimental artifact: {output}")
print("Run both models against this JAR and the official JAR at ALL_OPT and BASIC_OPT before use.")
