"""Apply reviewed Phase 4 integration edits in the isolated work branch.
No reference/Kotlin source is modified; replacements fail closed on divergence.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def replace(path, before, after):
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    if before not in text:
        if after in text:
            return
        raise SystemExit('Source diverged: ' + path)
    if text.count(before) != 1:
        raise SystemExit('Ambiguous source edit: ' + path)
    file.write_text(text.replace(before, after), encoding='utf-8')

replace('flutterDesktop/lib/core/platform/accounts/browser_session.dart',
    "Future<Map<String, dynamic>> command(String method, [Map<String, Object?> params = const {}], {String? session}) async {",
    "Future<Map<String, dynamic>> command(String method, Map<String, Object?> params, {String? session}) async {")
replace('flutterDesktop/lib/core/platform/accounts/browser_session.dart',
    "cdp.command('Storage.getCookies')", "cdp.command('Storage.getCookies', const {})")
replace('flutterDesktop/lib/core/platform/accounts/browser_session.dart',
    'header.length > 65536', 'header.length > 32768')
replace('flutterDesktop/lib/core/platform/accounts/browser_session.dart',
    "final result = Completer<Map<String, dynamic>>();\n    final requests",
    "final result = Completer<Map<String, dynamic>>();\n    unawaited(result.future.then<void>((_) {}, onError: (Object _, StackTrace __) {}));\n    final requests")
replace('flutterDesktop/lib/features/online/data/account_store.dart',
    "    if (rows.isEmpty) { await vault.delete(scope); } else { await vault.write(encoded, scope); }\n    scope.check();\n    _sessions = List.unmodifiable(rows);\n    _active = Map.unmodifiable(active);",
    "    try {\n      if (rows.isEmpty) { await vault.delete(scope); } else { await vault.write(encoded, scope); }\n    } catch (_) {\n      // An interrupted OS operation may have committed: reload before writing.\n      _loaded = false;\n      rethrow;\n    }\n    _sessions = List.unmodifiable(rows);\n    _active = Map.unmodifiable(active);\n    scope.check();")
replace('flutterDesktop/native/audio/CMakeLists.txt',
    'option(BC_SANITIZE "Address/undefined behavior instrumentation" OFF)',
    'option(BC_SANITIZE "Address/undefined behavior instrumentation" OFF)\nadd_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../accounts" "${CMAKE_CURRENT_BINARY_DIR}/accounts")')
replace('flutterDesktop/native/audio/host.cmake',
    'add_dependencies(${BINARY_NAME} bitchord_audio)',
    'add_dependencies(${BINARY_NAME} bitchord_audio bitchord_secrets)\ninstall(TARGETS bitchord_secrets RUNTIME DESTINATION "${CMAKE_INSTALL_PREFIX}" COMPONENT Runtime)')
replace('flutterDesktop/tool/install_linux_ci.py',
    "'libsqlite3-dev', 'pulseaudio', 'pulseaudio-utils', 'ffmpeg'",
    "'libsqlite3-dev', 'libsecret-1-dev', 'gnome-keyring', 'pulseaudio', 'pulseaudio-utils', 'ffmpeg'")
print('Applied reviewed account integration edits; reference files untouched.')
