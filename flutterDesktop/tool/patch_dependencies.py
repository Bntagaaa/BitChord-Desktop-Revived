"""Apply verified upstream bugfixes to cached pub dependencies.

Specifically: desktop_webview_window 0.4.2 contains a known crash on app exit
on Windows (upstream PR #557, commit b2c41d0959347b9c05ced87df552ec9600c9b0b4):
calling `channel_->SetMethodCallHandler(nullptr)` inside `~WebviewWindowPlugin()`
causes `~FlutterWindowsEngine` to dereference a null engine in flutter_windows.dll
with STATUS_FATAL_USER_CALLBACK_EXCEPTION (0xC000041D / -1073740771).
"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_desktop_webview_window():
    search_dirs = []
    pub_cache = os.environ.get('PUB_CACHE')
    if pub_cache:
        search_dirs.append(Path(pub_cache))
    local_app_data = os.environ.get('LOCALAPPDATA')
    if local_app_data:
        search_dirs.append(Path(local_app_data) / 'Pub' / 'Cache')
    search_dirs.append(Path.home() / '.pub-cache')
    search_dirs.append(ROOT / 'windows' / 'flutter' / 'ephemeral' / '.plugin_symlinks' / 'desktop_webview_window')

    targets = set()
    for base in search_dirs:
        if not base.exists():
            continue
        for candidate in base.rglob('web_view_window_plugin.cc'):
            targets.add(candidate.resolve())

    patched_count = 0
    target_needle = 'channel_->SetMethodCallHandler(nullptr);'
    replacement = (
        '// Upstream bugfix (#557): The handler is deliberately left registered. A plugin is destroyed while\n'
        '  // its engine shuts down, and ~FlutterWindowsEngine detaches the messenger\n'
        '  // from the engine before it runs the registrar\'s destruction callback, so\n'
        '  // clearing the handler here dereferences a null engine and crashes the app\n'
        '  // on exit. An engine that is shutting down dispatches nothing more to it.'
    )

    for target in targets:
        try:
            content = target.read_text(encoding='utf-8')
            if target_needle in content:
                content = content.replace(target_needle, replacement, 1)
                target.write_text(content, encoding='utf-8')
                print(f'Patched Windows exit crash in {target}')
                patched_count += 1
            elif 'Upstream bugfix (#557)' in content:
                print(f'Already patched: {target}')
        except Exception as error:
            print(f'Could not patch {target}: {error}')

    print(f'Dependency patch complete. Targets checked: {len(targets)}, patched: {patched_count}')


def main():
    patch_desktop_webview_window()


if __name__ == '__main__':
    main()
