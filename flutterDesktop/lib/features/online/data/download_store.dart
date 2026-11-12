import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';

import '../domain/catalog.dart';
import 'range_source.dart';

final class OfflineTrack {
  const OfflineTrack(this.entry, this.path, this.bytes);
  final CatalogEntry entry;
  final String path;
  final int bytes;
}

// App-owned directory only. Journals contain identity, validators and hashes,
// never signed URLs/headers/cookies. A partial file is never returned as audio.
final class DownloadStore {
  DownloadStore(this.root);
  final Directory root;
  bool _busy = false;
  String _key(CatalogEntry entry) =>
      sha256.convert(utf8.encode(entry.key)).toString();
  File _file(String key, String suffix) => File('${root.path}/$key.$suffix');
  Future<void> _journal(String key, Map<String, dynamic> data) async {
    final tmp = _file(key, 'json.tmp');
    await tmp.writeAsString(jsonEncode(data), flush: true);
    await tmp.rename(_file(key, 'json').path);
  }

  Future<Map<String, dynamic>?> _read(String key) async {
    try {
      final f = _file(key, 'json');
      if (!await f.exists() || await f.length() > 2 * 1024 * 1024) return null;
      return jsonDecode(await f.readAsString()) as Map<String, dynamic>;
    } catch (_) {
      return null;
    }
  }

  Future<bool> _verify(
    File file,
    Map<String, dynamic> journal, {
    bool partial = false,
  }) async {
    if (!await file.exists()) return false;
    final hashes = journal['hashes'];
    final total = journal['total'];
    if (hashes is! List ||
        total is! int ||
        total <= 0 ||
        total > RangeSource.maxBytes ||
        hashes.length > 16384)
      return false;
    final committed = journal['committed'];
    if (committed is! int ||
        committed < 0 ||
        committed > total ||
        (!partial && committed != total))
      return false;
    if (await file.length() < committed ||
        (!partial && await file.length() != committed))
      return false;
    final input = await file.open();
    try {
      var bytes = 0;
      for (final hash in hashes) {
        final count = total - bytes < RangeSource.span
            ? total - bytes
            : RangeSource.span;
        final data = await input.read(count);
        if (data.length != count || sha256.convert(data).toString() != hash)
          return false;
        bytes += count;
      }
      return bytes == committed;
    } finally {
      await input.close();
    }
  }

  Future<OfflineTrack> save(
    CatalogEntry entry,
    RangeSource source, {
    void Function(int, int)? progress,
  }) async {
    if (_busy) throw const ProviderFailure(ProviderProblem.unavailable);
    source.media.check();
    if (!source.media.downloadsAllowed)
      throw const ProviderFailure(ProviderProblem.unsupported);
    _busy = true;
    try {
      await root.create(recursive: true);
      final key = _key(entry), part = _file(_key(entry), 'part');
      await source.chunk(0);
      var journal = await _read(key);
      var committed = 0;
      final hashes = <String>[];
      if (journal != null &&
          journal['key'] == entry.key &&
          journal['total'] == source.total &&
          source.validator != null &&
          journal['validator'] == source.validator &&
          await _verify(part, journal, partial: true)) {
        committed = journal['committed'] as int;
        hashes.addAll((journal['hashes'] as List).cast<String>());
      }
      journal = {
        'version': 1,
        'key': entry.key,
        'provider': entry.provider,
        'id': entry.id,
        'title': entry.title,
        'subtitle': entry.subtitle,
        'total': source.total,
        'validator': source.validator,
        'hashes': hashes,
        'committed': committed,
        'complete': false,
      };
      final output = await part.open(mode: FileMode.append);
      try {
        await output.truncate(committed);
        await output.setPosition(committed);
        await _journal(key, journal);
        while (committed < source.total!) {
          source.scope.check();
          final data = await source.chunk(hashes.length);
          await output.writeFrom(data);
          await output.flush();
          committed += data.length;
          hashes.add(sha256.convert(data).toString());
          journal['committed'] = committed;
          await _journal(key, journal);
          progress?.call(committed, source.total!);
        }
      } finally {
        await output.close();
      }
      source.scope.check();
      journal['complete'] = true;
      await _journal(key, journal);
      await part.rename(_file(key, 'audio').path);
      return OfflineTrack(entry, _file(key, 'audio').path, committed);
    } on ProviderFailure {
      rethrow;
    } on FileSystemException {
      throw const ProviderFailure(ProviderProblem.integrity);
    } finally {
      _busy = false;
    }
  }

  Future<List<OfflineTrack>> list() async {
    if (!await root.exists()) return [];
    final result = <OfflineTrack>[];
    await for (final file in root.list(followLinks: false)) {
      if (file is! File ||
          !RegExp(r'^[a-f0-9]{64}\.json$').hasMatch(file.uri.pathSegments.last))
        continue;
      final key = file.uri.pathSegments.last.split('.').first;
      final j = await _read(key);
      if (j == null ||
          j['complete'] != true ||
          j['provider'] is! String ||
          j['id'] is! String ||
          j['title'] is! String)
        continue;
      final entry = CatalogEntry(
        provider: j['provider'] as String,
        id: j['id'] as String,
        title: j['title'] as String,
        subtitle: j['subtitle'] as String? ?? '',
      );
      if (_key(entry) != key) continue;
      var audio = _file(key, 'audio');
      // Recover a crash after the final journal but before promotion.
      if (!await audio.exists() && await _verify(_file(key, 'part'), j))
        audio = await _file(key, 'part').rename(audio.path);
      if (await _verify(audio, j))
        result.add(OfflineTrack(entry, audio.path, j['total'] as int));
    }
    return result;
  }

  Future<void> remove(CatalogEntry entry) async {
    if (_busy) throw const ProviderFailure(ProviderProblem.unavailable);
    for (final suffix in ['json', 'json.tmp', 'audio', 'part']) {
      final f = _file(_key(entry), suffix);
      if (await f.exists()) await f.delete();
    }
  }
}
