"""Stream diagnostics, preserve exit status and expose bounded public failure context."""
from collections import deque
from pathlib import Path
import json
import re
import shutil
import subprocess
import sys


def escape(message):
    return message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main():
    label, *command = sys.argv[1:]
    if not command or not re.fullmatch(r'[a-z0-9-]+', label):
        raise SystemExit('Expected a simple stage label and a command.')
    evidence = Path('verification')
    evidence.mkdir(exist_ok=True)
    executable = shutil.which(command[0])
    if not executable:
        print(f'::error::{escape(label + ": executable unavailable: " + command[0])}')
        return 127
    tail = deque(maxlen=25)
    counts = {'passed': 0, 'failed': 0, 'skipped': 0}
    with (evidence / (label + '.log')).open('w', encoding='utf-8') as log:
        with subprocess.Popen([executable, *command[1:]], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              encoding='utf-8', errors='replace') as process:
            for line in process.stdout:
                line = re.sub(r'(?im)^.*(?:authorization:|bearer\s|gh[opsu]_[a-zA-Z0-9_]+).*$', '[redacted diagnostic line]', line)
                tail.append(line.rstrip())
                log.write(line)
                log.flush()
                print(line, end='', flush=True)
                if label == 'tests':
                    try:
                        event = json.loads(line)
                    except (json.JSONDecodeError, ValueError):
                        continue
                    if not isinstance(event, dict):
                        continue
                    if event.get('type') == 'testStart':
                        name = event.get('test', {}).get('name', '')[:400]
                        print(f'\n::notice title=test-start::{escape(name)}', flush=True)
                    if event.get('type') == 'testDone' and not event.get('hidden', False):
                        if event.get('skipped'):
                            counts['skipped'] += 1
                        elif event.get('result') == 'success':
                            counts['passed'] += 1
                        elif event.get('result') in {'failure', 'error'}:
                            counts['failed'] += 1
            code = process.wait()
    if code:
        context = '\n'.join(tail)[-5000:]
        print(f'\n::error title={label}::{escape(context or "Command failed with no output")}', flush=True)
    if label == 'tests':
        (evidence / 'test-summary.json').write_text(json.dumps({**counts, 'exit_code': code}, indent=2) + '\n')
        print(f'\n::notice title=tests::{counts["passed"]} passed; {counts["failed"]} failed; {counts["skipped"]} skipped', flush=True)
        if code == 0 and counts['passed'] == 0:
            print('::error title=tests::No executed passing tests were reported.', flush=True)
            return 1
    return code


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'\n::error title=validation-driver::{escape(f"{type(error).__name__}: {error}")}', flush=True)
        raise
