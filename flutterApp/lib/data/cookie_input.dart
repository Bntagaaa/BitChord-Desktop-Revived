/// Strict parsing for an explicitly supplied YouTube cookie export.
class CookieInput {
  /// Accepts a single Cookie header or the standard Netscape export format.
  static String parse(String input) {
    final value = input.trim();
    if (!value.contains('\n') && !value.contains('\t')) {
      return value.replaceFirst(
        RegExp(r'^Cookie:\s*', caseSensitive: false),
        '',
      );
    }
    final cookies = <String, String>{};
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    for (var line in value.split('\n')) {
      line = line.trim();
      if (line.startsWith('#HttpOnly_')) {
        line = line.substring('#HttpOnly_'.length);
      }
      if (line.startsWith('#') || line.isEmpty) continue;
      final fields = line.split('\t');
      if (fields.length < 7) continue;
      final host = fields[0].replaceFirst(RegExp(r'^\.'), '').toLowerCase();
      if (host != 'youtube.com' && !host.endsWith('.youtube.com')) continue;
      final expires = int.tryParse(fields[4]) ?? 0;
      if (expires != 0 && expires < now) continue;
      if (RegExp(r'^[A-Za-z0-9_\-]+$').hasMatch(fields[5]) &&
          !fields[6].contains(';') &&
          !fields[6].contains('\r')) {
        cookies[fields[5]] = fields[6];
      }
    }
    return cookies.entries.map((e) => '${e.key}=${e.value}').join('; ');
  }
}
