"""Expose compiler and JUnit failures as annotations alongside the saved reports."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def annotate(title, message):
    message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={title}::{message}")


log = Path("desktop-tests.log")
if log.exists():
    errors = [line for line in log.read_text(errors="replace").splitlines()
              if re.search(r"(^e: |error:|Execution failed for task|^> .*[Ff]ail)", line)]
    for error in errors[:30]:
        annotate("Desktop Gradle check", error)

for module in ("desktopRuntime", "desktopUi", "sharedUi", "shared", "desktopApp"):
    for report in Path(module, "build/test-results").glob("**/TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            for failure in (*case.findall("failure"), *case.findall("error")):
                annotate(f"{case.get('classname')}.{case.get('name')}",
                         f"{case.get('classname')}.{case.get('name')}\n" +
                         failure.get("message", "") + "\n" + "\n".join((failure.text or "").splitlines()[:12]))
