#!/usr/bin/env python3
"""Run the pure playback assertions when the host cannot execute Dart JIT.

Normal verification remains `flutter test`. This intentionally covers only the
fixed pure-Dart suites below. The temporary copies use the same test_api and
matcher implementations exported by flutter_test, with no assertion changes.
Flutter controller/widget/native-renderer tests are not represented by this run.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import urljoin


SUITES = (
    "queue_timeline_test",
    "equalizer_test",
    "stream_resolver_test",
    "range_media_proxy_test",
    "transition_planner_test",
)

RUNNER = """
import 'dart:io';
import 'package:test_api/src/backend/declarer.dart';
import 'package:test_api/src/backend/group.dart';
import 'package:test_api/src/backend/test.dart';
import 'package:test_api/src/backend/suite.dart';
import 'package:test_api/src/backend/suite_platform.dart';
import 'package:test_api/src/backend/runtime.dart';
import 'package:test_api/src/backend/compiler.dart';
import 'package:test_api/src/backend/operating_system.dart';
IMPORTS

Future<void> main() async {
  final declarations = Declarer(collectTraces: true);
  declarations.declare(() {
    DECLARATIONS
  });
  final suite = Suite(declarations.build(), SuitePlatform(Runtime.vm,
    compiler: Compiler.exe, os: OperatingSystem.linux));
  var passed = 0, failed = 0;
  Future<void> runGroup(Group group, List<Group> parents) async {
    for (final entry in group.entries) {
      if (entry is Group) await runGroup(entry, [...parents, entry]);
      if (entry is Test) {
        final live = entry.load(suite, groups: parents);
        await live.run();
        if (live.state.result.isPassing) {
          passed++;
          stdout.writeln('PASS ${entry.name}');
        } else {
          failed++;
          stderr.writeln('FAIL ${entry.name}');
          for (final error in live.errors) {
            stderr.writeln(error.error);
            stderr.writeln(error.stackTrace);
          }
        }
        await live.close();
      }
    }
  }
  await runGroup(suite.group, [suite.group]);
  stdout.writeln('AOT playback tests: $passed passed, $failed failed');
  exitCode = failed == 0 ? 0 : 1;
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dart", default=shutil.which("dart"))
    args = parser.parse_args()
    if not args.dart:
        parser.error("Pass --dart /path/to/flutter/bin/cache/dart-sdk/bin/dart")
    app = Path(__file__).resolve().parents[2]
    config_file = app / ".dart_tool/package_config.json"
    if not config_file.is_file():
        parser.error("Run flutter pub get in flutterApp first.")
    config = json.loads(config_file.read_text())
    for package in config["packages"]:
        package["rootUri"] = urljoin(config_file.as_uri(), package["rootUri"])
    environment = dict(os.environ, CI="true", FLUTTER_SUPPRESS_ANALYTICS="true",
                       DASH__SUPPRESS_ANALYTICS="true")
    with tempfile.TemporaryDirectory(prefix="bitchord-playback-aot-") as directory:
        temporary = Path(directory)
        for name in SUITES:
            source = (app / f"test/playback/{name}.dart").read_text()
            original = "import 'package:flutter_test/flutter_test.dart';"
            if source.count(original) != 1:
                raise RuntimeError(f"Unexpected test import in {name}")
            source = source.replace(original,
                "import 'package:matcher/expect.dart';\n"
                "import 'package:test_api/scaffolding.dart';")
            (temporary / f"{name}.dart").write_text(source)
        (temporary / "package_config.json").write_text(json.dumps(config))
        imports = "\n".join(f"import '{name}.dart' as suite{i};"
                            for i, name in enumerate(SUITES))
        declarations = "\n".join(f"suite{i}.main();" for i in range(len(SUITES)))
        (temporary / "main.dart").write_text(
            RUNNER.replace("IMPORTS", imports).replace("DECLARATIONS", declarations))
        executable = temporary / ("playback_tests.exe" if os.name == "nt" else "playback_tests")
        subprocess.run([args.dart, "compile", "exe", "--packages=package_config.json",
                        "main.dart", "-o", str(executable)],
                       cwd=temporary, env=environment, check=True)
        subprocess.run([str(executable)], cwd=temporary, env=environment, check=True)


if __name__ == "__main__":
    main()
