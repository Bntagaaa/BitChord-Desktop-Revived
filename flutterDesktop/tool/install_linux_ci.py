"""Install hosted Ubuntu prerequisites using signed official HTTPS repositories."""
from pathlib import Path
import subprocess
import tempfile


def main():
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    if release.get('ID', '').strip('"') != 'ubuntu' or release.get('VERSION_ID', '').strip('"') != '22.04':
        raise SystemExit('This setup is only for the Ubuntu 22.04 CI runner.')
    # The hosted mirror+file list previously selected an unresolvable Azure
    # mirror. Scope this override to these two commands, leaving OS files alone.
    with tempfile.TemporaryDirectory(prefix='bitchord-apt-') as temporary:
        sources = Path(temporary) / 'ubuntu.sources'
        sources.write_text(
            'Types: deb\nURIs: https://archive.ubuntu.com/ubuntu\n'
            'Suites: jammy jammy-updates jammy-backports\n'
            'Components: main restricted universe multiverse\n'
            'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\n'
            'Types: deb\nURIs: https://security.ubuntu.com/ubuntu\n'
            'Suites: jammy-security\nComponents: main restricted universe multiverse\n'
            'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n')
        command = ['sudo', 'apt-get', '-o', f'Dir::Etc::sourcelist={sources}',
                   '-o', 'Dir::Etc::sourceparts=-', '-o', 'Acquire::Retries=2',
                   '-o', 'Acquire::http::Timeout=30', '-o', 'Acquire::https::Timeout=30',
                   '-o', 'APT::Update::Error-Mode=any']
        subprocess.run([*command, 'update'], check=True)
        subprocess.run([*command, 'install', '-y', '--no-install-recommends',
                        'clang', 'cmake', 'ninja-build', 'pkg-config', 'libgtk-3-dev',
                        'liblzma-dev', 'libstdc++-12-dev', 'xvfb', 'xauth', 'xdotool',
                        'openbox', 'imagemagick', 'dbus-x11'], check=True)


if __name__ == '__main__':
    main()
