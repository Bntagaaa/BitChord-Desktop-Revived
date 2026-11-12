"""Safe anonymous yt-dlp runtime diagnostic with categorical output only.

For a known public test-video only. Never print raw stderr, metadata, signed
URLs, tokens, request headers, cookies, or network responses. Does not
download media. Tests the exact packaged arguments and a safe client fallback.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

PUBLIC_TEST_VIDEO = "jNQXAC9IVRw"
EXTRACTOR_TIMEOUT = 70


def find_runtime(root: Path) -> Path:
    results = [p for p in root.rglob("youtube_runtime") if
               p.is_dir() and (p / "yt-dlp").is_file() and (p / "node").is_file()]
    if len(results) != 1:
        raise RuntimeError("Expected precisely one bundled YouTube runtime")
    return results[0]


def run(runtime: Path, player_client: str) -> dict:
    yt = runtime / "yt-dlp"
    node = runtime / "node"
    plugin = runtime / "plugins"
    server = runtime / "server"
    for path in [yt, node]:
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
    env = {k: os.environ[k] for k in
           ["PATH", "HOME", "TMPDIR", "XDG_RUNTIME_DIR", "XDG_CACHE_HOME"] if k in os.environ}
    args = [
        str(yt), "--ignore-config", "--no-plugin-dirs",
        "--plugin-dirs", str(plugin), "--extractor-args",
        f"youtube:player_client={player_client};youtubepot-bgutilscript:server_home={server}",
        "--no-remote-components", "--no-cache-dir", "--no-playlist",
        "--skip-download", "--dump-single-json", "--verbose",
        "--socket-timeout", "10", "--retries", "0",
        "--extractor-retries", "0", "--no-js-runtimes", "--js-runtimes",
        f"node:{node}", "-f", "bestaudio[protocol=https][abr<=128]",
        "--", f"https://www.youtube.com/watch?v={PUBLIC_TEST_VIDEO}"
    ]
    try:
        proc = subprocess.run(args, cwd=runtime, env=env, capture_output=True,
                              timeout=EXTRACTOR_TIMEOUT)
        err = proc.stderr[:300_000].decode("utf-8", "replace").lower()
        out = proc.stdout[:4_000_000].decode("utf-8", "replace")
        result = {
            "client": player_client,
            "extractor_exit": proc.returncode,
            "bgutil_plugin_loaded": "bgutil:script-node" in err,
            "po_token_generation_attempted": "generating a" in err and "po token" in err,
            "po_token_generation_failed": "pot:" in err and
                ("error" in err or "failed" in err),
            "js_runtime_missing": "no supported javascript runtime" in err or
                "js runtimes: none" in err,
            "challenge_solve_failed": "challenge solving failed" in err or
                "challenge solver" in err and "failed" in err,
            "auth_required": "sign in to confirm" in err or
                "login_required" in err,
            "format_missing": "requested format is not available" in err or
                "only images are available" in err,
            "geo_unavailable": "not available in your country" in err,
            "http_403": "http error 403" in err or "http 403" in err,
            "unsupported_yt_client": "unsupported client" in err,
            "plugin_error": "error loading plugin" in err,
            "error_kinds": sorted(set(re.findall(
                r"(?:error|warning):\\s*(?:\\[[a-z0-9_ -]+\\]\\s*)?([a-z -]{1,38})",
                err
            )))[:6],
            "json_selected": False,
        }
        if proc.returncode == 0:
            try:
                data = json.loads(out)
                result["json_selected"] = (data.get("vcodec") == "none" and
                    data.get("protocol") == "https" and bool(data.get("url")))
            except (ValueError, AttributeError):
                pass
        return result
    except subprocess.TimeoutExpired:
        return {"client": player_client, "extractor_exit": "TIMEOUT"}


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: youtube_probe.py <extracted-action-artifact-root>")
    runtime = find_runtime(Path(sys.argv[1]).resolve())
    results = [run(runtime, c) for c in ["mweb", "web_safari"]]
    report = {"youtube_media_probe": "categorical only", "cases": results}
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
