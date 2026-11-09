// Port source: DesktopLocalArtwork.kt @ 348b613a.
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

const _maxImage = 20 * 1024 * 1024;

class LocalArtworkCache {
  const LocalArtworkCache(this.directory);
  final String directory;
  Future<String?> artwork(String path) {
    final cache = directory;
    return Isolate.run(() => _artwork(path, cache));
  }
}

String? _extension(List<int> bytes) {
  if (bytes.length >= 3 && bytes[0] == 255 && bytes[1] == 216 && bytes[2] == 255) return 'jpg';
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length >= 8 && List.generate(8, (i) => bytes[i] == signature[i]).every((value) => value)) return 'png';
  return null;
}

String? _artwork(String path, String cachePath) {
  try {
    final audio = File(path).absolute;
    final stat = audio.statSync();
    final files = audio.parent.listSync().whereType<File>().toList();
    File? cover;
    for (final name in ['cover', 'folder', 'front', 'album', 'artwork']) {
      for (final extension in ['jpg', 'jpeg', 'png']) {
        cover ??= files.where((file) => file.uri.pathSegments.last.toLowerCase() == '$name.$extension').firstOrNull;
      }
    }
    final coverStat = cover?.statSync();
    final key = sha256.convert(utf8.encode([
      '2', audio.path, stat.size, stat.modified.toIso8601String(),
      cover?.path ?? '', coverStat?.size ?? '', coverStat?.modified.toIso8601String() ?? '',
    ].join('|'))).toString().substring(0, 32);
    final cache = Directory(cachePath)..createSync(recursive: true);
    for (final extension in ['jpg', 'png']) {
      final existing = File('${cache.path}/$key.$extension');
      if (existing.existsSync() && existing.lengthSync() > 0) return existing.uri.toString();
    }
    final negative = File('${cache.path}/$key.none');
    if (negative.existsSync()) return null;
    var image = _embedded(audio);
    if (_extension(image ?? const []) == null) image = null;
    if (image == null && cover != null && cover.lengthSync() > 0 && cover.lengthSync() <= _maxImage) {
      final candidate = cover.readAsBytesSync();
      if (_extension(candidate) != null) image = candidate;
    }
    final extension = _extension(image ?? const []);
    if (extension == null || image == null) {
      negative.writeAsBytesSync(const []);
      return null;
    }
    final target = File('${cache.path}/$key.$extension')..writeAsBytesSync(image, flush: true);
    return target.uri.toString();
  } on FileSystemException { return null; }
}

Uint8List _read(RandomAccessFile file, int length) {
  if (length < 0 || length > _maxImage || length > file.lengthSync() - file.positionSync()) {
    throw const FormatException('Invalid metadata size');
  }
  final data = file.readSync(length);
  if (data.length != length) throw const FormatException('Truncated metadata');
  return data;
}
int _u32(Uint8List data, int offset) => ByteData.sublistView(data).getUint32(offset);
int _syncSafe(Uint8List data, int offset) =>
  ((data[offset] & 127) << 21) | ((data[offset + 1] & 127) << 14) |
  ((data[offset + 2] & 127) << 7) | (data[offset + 3] & 127);

Uint8List? _embedded(File file) {
  RandomAccessFile? input;
  try {
    input = file.openSync();
    final extension = file.path.split('.').last.toLowerCase();
    return switch (extension) {
      'flac' => _flac(input),
      'mp3' => _mp3(input),
      'm4a' || 'aac' => _mp4(input, 0, input.lengthSync(), 0, false),
      _ => null,
    };
  } on FileSystemException { return null; }
  on FormatException { return null; }
  on RangeError { return null; }
  finally { input?.closeSync(); }
}

Uint8List? _flac(RandomAccessFile input) {
  if (ascii.decode(_read(input, 4)) != 'fLaC') return null;
  while (input.positionSync() + 4 <= input.lengthSync()) {
    final header = _read(input, 4);
    final length = (header[1] << 16) | (header[2] << 8) | header[3];
    final end = input.positionSync() + length;
    if (end > input.lengthSync()) break;
    if ((header[0] & 127) == 6 && length >= 32) {
      final data = _read(input, length);
      final mimeLength = _u32(data, 4);
      if (mimeLength <= 256 && 8 + mimeLength + 4 <= data.length) {
        final descriptionLength = _u32(data, 8 + mimeLength);
        final sizeOffset = 12 + mimeLength + descriptionLength + 16;
        if (descriptionLength <= 4096 && sizeOffset + 4 <= data.length) {
          final size = _u32(data, sizeOffset);
          if (size > 0 && size <= _maxImage && size <= data.length - sizeOffset - 4) {
            final image = Uint8List.sublistView(data, sizeOffset + 4, sizeOffset + 4 + size);
            if (_extension(image) != null) return image;
          }
        }
      }
    }
    input.setPositionSync(end);
    if ((header[0] & 128) != 0) break;
  }
  return null;
}

Uint8List? _mp3(RandomAccessFile input) {
  final header = _read(input, 10);
  if (ascii.decode(header.sublist(0, 3), allowInvalid: true) != 'ID3') return null;
  final major = header[3];
  if (major < 3 || major > 4) return null;
  final tagLength = _syncSafe(header, 6);
  if (tagLength <= 0 || tagLength > 4 * 1024 * 1024) return null;
  final tag = _read(input, tagLength);
  var offset = 0;
  if ((header[5] & 64) != 0) {
    if (tag.length < 4) return null;
    offset = major == 4 ? _syncSafe(tag, 0) : _u32(tag, 0) + 4;
    if (offset < 4 || offset > tag.length) return null;
  }
  while (offset + 10 <= tag.length) {
    final name = ascii.decode(tag.sublist(offset, offset + 4), allowInvalid: true);
    if (tag[offset] == 0) break;
    final size = major == 4 ? _syncSafe(tag, offset + 4) : _u32(tag, offset + 4);
    if (size <= 0 || size > tag.length - offset - 10) break;
    final flags = tag[offset + 9];
    final start = offset + 10;
    offset = start + size;
    if (name != 'APIC') continue;
    if ((major == 3 && (flags & 192) != 0) || (major == 4 && (flags & 12) != 0)) continue;
    final prefix = major == 3 ? ((flags & 32) != 0 ? 1 : 0) :
      ((flags & 64) != 0 ? 1 : 0) + ((flags & 1) != 0 ? 4 : 0);
    if (size <= prefix) continue;
    var image = _apic(tag, start + prefix, size - prefix);
    if (image == null) continue;
    if ((header[5] & 128) != 0 || (major == 4 && (flags & 2) != 0)) {
      final clean = <int>[];
      for (var i = 0; i < image.length; i++) {
        clean.add(image[i]);
        if (image[i] == 255 && i + 1 < image.length && image[i + 1] == 0) i++;
      }
      image = Uint8List.fromList(clean);
    }
    if (_extension(image) != null) return image;
  }
  return null;
}

Uint8List? _apic(Uint8List data, int start, int size) {
  if (size < 4) return null;
  final end = start + size;
  var position = start + 1;
  while (position < end && data[position] != 0) { position++; }
  position++;
  if (position >= end) return null;
  position++; // picture type
  final utf16 = data[start] == 1 || data[start] == 2;
  if (utf16) {
    if (position + 1 < end && ((data[position] == 255 && data[position + 1] == 254) ||
        (data[position] == 254 && data[position + 1] == 255))) position += 2;
    var terminated = false;
    while (position + 1 < end) {
      if (data[position] == 0 && data[position + 1] == 0) {
        position += 2; terminated = true; break;
      }
      position += 2;
    }
    if (!terminated) return null;
  } else {
    while (position < end && data[position] != 0) { position++; }
    if (position >= end) return null;
    position++;
  }
  return position >= end ? null : Uint8List.fromList(data.sublist(position, end));
}

Uint8List? _mp4(RandomAccessFile input, int start, int end, int depth, bool inCover) {
  if (depth > 8) return null;
  var offset = start;
  while (end - offset >= 8) {
    input.setPositionSync(offset);
    final header = _read(input, 8);
    var size = _u32(header, 0);
    final type = ascii.decode(header.sublist(4), allowInvalid: true);
    var headerSize = 8;
    if (size == 0) size = end - offset;
    else if (size == 1) {
      if (end - offset < 16) return null;
      size = ByteData.sublistView(_read(input, 8)).getUint64(0); headerSize = 16;
    }
    if (size < headerSize || size > end - offset) return null;
    final dataStart = offset + headerSize;
    final boxEnd = offset + size;
    if (const {'moov','udta','meta','ilst','covr'}.contains(type)) {
      final childStart = dataStart + (type == 'meta' ? 4 : 0);
      if (childStart <= boxEnd) {
        final image = _mp4(input, childStart, boxEnd, depth + 1, type == 'covr');
        if (image != null) return image;
      }
    } else if (type == 'data' && inCover) {
      final length = boxEnd - dataStart - 8;
      if (length > 0 && length <= _maxImage) {
        input.setPositionSync(dataStart + 8);
        final image = _read(input, length);
        if (_extension(image) != null) return image;
      }
    }
    offset = boxEnd;
  }
  return null;
}
