"""Preserve command exit status and publish bounded failure context in annotations.

GitHub's public step status/annotations remain readable when API/log access is
unavailable. Commands here are build/validation tools, never secret-bearing CLI
requests. Full output is retained in the validation artifact.
"""
from pathlib import Path
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
    result = subprocess.run([executable, *command[1:]], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding='utf-8', errors='replace')
    # Defend against accidental credential-bearing diagnostic lines. No env dump.
    output = re.sub(r'(?im)^.*(?:authorization:|bearer\s|gh[opsu]_[a-zA-Z0-9_]+).*$','[redacted diagnostic line]', result.stdout)
    (evidence / (label + '.log')).write_text(output, encoding='utf-8')
    print(output, end='')
    if result.returncode:
        context = '\n'.join(output.splitlines()[-25:])[-5000:]
        print(f'\n::error title={label}::{escape(context or "Command failed with no output")}', flush=True)
    return result.returncode


if __name__ == '__main__':
    # Windows consoles can default to cp1252 while Flutter emits Unicode.
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    try:
        raise SystemExit(main())
    except Exception as error:
        message = f'{type(error).__name__}: {error}'
        print(f'\n::error title=validation-driver::{escape(message)}', flush=True)
        raise

