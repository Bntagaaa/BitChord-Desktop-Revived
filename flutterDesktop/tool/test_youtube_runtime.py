"""Exercise actual packaged yt-dlp: namespace routing, plugin loading, stdin.

All account values/media URLs below are synthetic and the audit performs NO
network access. The temporary audit extractor is never shipped in the app.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

AUDIT = r'''
import os
from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.extractor.youtube import YoutubeIE

class BitChordAuditIE(InfoExtractor):
    _VALID_URL = r'bitchord-audit:(?P<id>[a-z]+)'
    def _real_extract(self, url):
        from yt_dlp_plugins.extractor.getpot_bgutil_script import BgUtilScriptNodePTP
        args = self.get_param('extractor_args')
        assert args['youtube']['player_client'] == ['mweb']
        assert args['youtubepot-bgutilscript']['server_home'] == [os.environ['AUDIT_SERVER_HOME']]
        assert not any('bgutilscript:' in key for key in args['youtube'])
        from yt_dlp_plugins.extractor.bitchord_session import import_session
        import_session(self)
        cookie = next(c for c in self._downloader.cookiejar if c.name == 'SAPISID')
        assert cookie.value == 'synthetic-never-real'
        assert cookie.domain == '.youtube.com' and cookie.secure
        assert self._downloader.get_info_extractor('Youtube')._initialize_pre_login.__module__ == 'yt_dlp_plugins.extractor.bitchord_session'
        return {'id': 'fixture', 'title': 'offline contract', 'url': 'https://example.invalid/fixture.mp3'}
'''


def main():
    binary, plugins, server = [Path(p).resolve() for p in sys.argv[1:]]
    with tempfile.TemporaryDirectory(prefix='bitchord-youtube-audit-') as temp:
        root = Path(temp)
        copied = root / 'plugins'
        shutil.copytree(plugins, copied)
        with zipfile.ZipFile(copied / 'audit.zip', 'w') as archive:
            archive.writestr('yt_dlp_plugins/extractor/bitchord_audit.py', AUDIT)
        env = {k: v for k, v in os.environ.items() if k in ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP')}
        env.update(HOME=temp, USERPROFILE=temp, XDG_CACHE_HOME=temp,
                   BITCHORD_YT_SESSION_PIPE='1', AUDIT_SERVER_HOME=str(server))
        args = [str(binary), '--ignore-config', '--no-plugin-dirs', '--plugin-dirs', str(copied),
                '--no-cache-dir', '--no-remote-components', '--skip-download', '--dump-single-json',
                '--extractor-args', 'youtube:player_client=mweb', '--extractor-args',
                'youtubepot-bgutilscript:server_home=' + str(server), 'bitchord-audit:inspect']
        trial = subprocess.run(args, input=json.dumps({'cookie': 'SAPISID=synthetic-never-real'}) + '\n',
                               text=True, capture_output=True, timeout=30, env=env)
        if trial.returncode:
            # The audit has no live data; still redact errors instead of growing
            # a precedent for printing arbitrary yt-dlp diagnostics in CI.
            raise SystemExit('Offline packaged YouTube plugin/argument contract FAILED')
        assert json.loads(trial.stdout)['id'] == 'fixture'
        assert 'synthetic-never-real' not in trial.stdout + trial.stderr
        assert not list(root.rglob('cookies.txt'))
        with zipfile.ZipFile(plugins / 'bgutil-ytdlp-pot-provider.zip') as archive:
            assert not any(name.endswith('getpot_bgutil_http.py') for name in archive.namelist())
        print('Actual yt-dlp namespace / script-provider / in-memory session contracts PASS (offline fixtures)')


if __name__ == '__main__':
    main()
