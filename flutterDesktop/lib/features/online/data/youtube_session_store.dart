import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

import 'youtube_session.dart';

/// Secure persistent storage for YouTube Music sessions.
/// Credentials are encrypted with a device-and-user-bound key, verified with an
/// HMAC authentication tag, and written atomically with restricted permissions.
/// Raw cookies and secrets are never logged, committed, or exposed.
final class YouTubeSessionStore {
  YouTubeSessionStore(this.storageDirectory);

  final Directory storageDirectory;

  static const _vaultFileName = 'bitchord_session_vault.v1';
  static const _saltFileName = '.session_salt';
  static const _version = 1;

  File get _vaultFile => File('${storageDirectory.path}/$_vaultFileName');
  File get _saltFile => File('${storageDirectory.path}/$_saltFileName');

  bool exists() => _vaultFile.existsSync();

  Uint8List _getOrCreateSalt() {
    if (!storageDirectory.existsSync()) {
      storageDirectory.createSync(recursive: true);
    }
    final saltFile = _saltFile;
    if (saltFile.existsSync()) {
      final bytes = saltFile.readAsBytesSync();
      if (bytes.length == 32) return bytes;
    }
    final random = Random.secure();
    final salt = Uint8List(32);
    for (var i = 0; i < 32; i++) {
      salt[i] = random.nextInt(256);
    }
    saltFile.writeAsBytesSync(salt, flush: true);
    if (!Platform.isWindows) {
      try {
        Process.runSync('chmod', ['0600', saltFile.path]);
      } catch (_) {}
    }
    return salt;
  }

  Uint8List _deriveKey(Uint8List salt) {
    final env = Platform.environment;
    final user = env['USER'] ?? env['USERNAME'] ?? 'bitchord_user';
    final host = Platform.localHostname;
    final os = Platform.operatingSystem;
    final seed = utf8.encode('bitchord-auth-vault-v1:$user@$host:$os');
    final hmac = Hmac(sha256, salt);
    return Uint8List.fromList(hmac.convert(seed).bytes);
  }

  Uint8List _crypt({
    required Uint8List key,
    required Uint8List nonce,
    required Uint8List input,
  }) {
    final output = Uint8List(input.length);
    var blockIndex = 0;
    for (var offset = 0; offset < input.length; offset += 32) {
      final counterBytes = ByteData(4)..setUint32(0, blockIndex++, Endian.big);
      final blockHmac = Hmac(sha256, key);
      final keystream = blockHmac
          .convert([...nonce, ...counterBytes.buffer.asUint8List()])
          .bytes;
      final chunkSize = min(32, input.length - offset);
      for (var i = 0; i < chunkSize; i++) {
        output[offset + i] = input[offset + i] ^ keystream[i];
      }
    }
    return output;
  }

  Future<void> save(YouTubeSession session) async {
    if (!storageDirectory.existsSync()) {
      await storageDirectory.create(recursive: true);
    }
    final salt = _getOrCreateSalt();
    final masterKey = _deriveKey(salt);
    final encKey = Uint8List.fromList(
      Hmac(sha256, masterKey).convert(utf8.encode('enc-stream')).bytes,
    );
    final macKey = Uint8List.fromList(
      Hmac(sha256, masterKey).convert(utf8.encode('auth-tag')).bytes,
    );

    final payload = jsonEncode({
      'v': _version,
      'cookie': session.cookie,
      'authUser': session.authUser,
      'version': session.version,
      'pageId': session.pageId,
      'dataSyncId': session.dataSyncId,
      'visitorData': session.visitorData,
      'savedAt': DateTime.now().millisecondsSinceEpoch,
    });
    final plainBytes = Uint8List.fromList(utf8.encode(payload));

    final random = Random.secure();
    final nonce = Uint8List(16);
    for (var i = 0; i < 16; i++) {
      nonce[i] = random.nextInt(256);
    }

    final cipherBytes = _crypt(key: encKey, nonce: nonce, input: plainBytes);
    final macHmac = Hmac(sha256, macKey);
    final tag = Uint8List.fromList(
      macHmac.convert([_version, ...nonce, ...cipherBytes]).bytes,
    );

    final result = BytesBuilder(copy: false);
    result.addByte(_version);
    result.add(nonce);
    result.add(tag);
    result.add(cipherBytes);

    final file = _vaultFile;
    final tempFile = File('${file.path}.tmp');
    await tempFile.writeAsBytes(result.takeBytes(), flush: true);
    if (!Platform.isWindows) {
      try {
        await Process.run('chmod', ['0600', tempFile.path]);
      } catch (_) {}
    }
    await tempFile.rename(file.path);
  }

  Future<Map<String, dynamic>?> loadData() async {
    final file = _vaultFile;
    if (!file.existsSync()) return null;

    try {
      final raw = await file.readAsBytes();
      // Minimum size: 1 (version) + 16 (nonce) + 32 (tag) + 1 (cipher) = 50
      if (raw.length < 50) return null;
      final version = raw[0];
      if (version != _version) return null;

      final nonce = raw.sublist(1, 17);
      final tag = raw.sublist(17, 49);
      final cipherBytes = raw.sublist(49);

      final salt = _getOrCreateSalt();
      final masterKey = _deriveKey(salt);
      final macKey = Uint8List.fromList(
        Hmac(sha256, masterKey).convert(utf8.encode('auth-tag')).bytes,
      );

      final expectedTag = Hmac(
        sha256,
        macKey,
      ).convert([version, ...nonce, ...cipherBytes]).bytes;
      // Constant-time tag comparison to prevent timing attacks
      var diff = 0;
      for (var i = 0; i < 32; i++) {
        diff |= tag[i] ^ expectedTag[i];
      }
      if (diff != 0) return null;

      final encKey = Uint8List.fromList(
        Hmac(sha256, masterKey).convert(utf8.encode('enc-stream')).bytes,
      );
      final plainBytes = _crypt(
        key: encKey,
        nonce: nonce,
        input: Uint8List.fromList(cipherBytes),
      );
      final decoded = jsonDecode(utf8.decode(plainBytes));
      if (decoded is! Map<String, dynamic>) return null;
      return decoded;
    } catch (_) {
      return null;
    }
  }

  Future<void> clear() async {
    try {
      final file = _vaultFile;
      if (file.existsSync()) {
        await file.delete();
      }
      final tmp = File('${file.path}.tmp');
      if (tmp.existsSync()) {
        await tmp.delete();
      }
    } catch (_) {}
  }
}
