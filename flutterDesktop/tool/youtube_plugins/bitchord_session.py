"""Verified BitChord session transport for the pinned yt-dlp runtime.

No browser scraping, environment/argument secrets, cookie file, or persistence.
Only BitChord's explicit pipe marker enables a bounded one-shot stdin read.
"""
import json
import os
import re
import sys

from yt_dlp.extractor.youtube import YoutubeIE
from yt_dlp.utils import ExtractorError

__all__ = []  # Register the override, not another independently selected IE.
_consumed = False


def import_session(extractor):
    global _consumed
    if _consumed or os.environ.get('BITCHORD_YT_SESSION_PIPE') != '1':
        return
    _consumed = True
    try:
        raw = sys.stdin.buffer.readline(131073)
        if len(raw) > 131072 or not raw.endswith(b'\n'):
            raise ValueError()
        payload = json.loads(raw)
        cookie = payload.get('cookie') if isinstance(payload, dict) else None
        if not isinstance(cookie, str) or not cookie or len(cookie) > 32768:
            raise ValueError()
        if any(ord(c) < 32 or ord(c) == 127 for c in cookie):
            raise ValueError()
        parsed = {}
        for item in cookie.split(';'):
            name, value = item.strip().split('=', 1)
            if not re.fullmatch(r'[!#$%&\x27*+.^_`|~0-9A-Za-z-]+', name):
                raise ValueError()
            if name in parsed and parsed[name] != value:
                raise ValueError()
            parsed[name] = value
        if len(parsed) > 128 or not any(
            name in parsed for name in ('SAPISID', '__Secure-3PAPISID', '__Secure-1PAPISID')
        ):
            raise ValueError()
        for name, value in parsed.items():
            extractor._set_cookie('.youtube.com', name, value, secure=True)
    except Exception:
        # Never include the payload, cookie value, URL or underlying exception.
        raise ExtractorError('BitChord session input rejected', expected=True) from None


class _YoutubeSessionIE(YoutubeIE, plugin_name='bitchord_session'):
    def _initialize_pre_login(self):
        import_session(self)
        return super()._initialize_pre_login()
