import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../../../features/online/domain/account_port.dart';
import '../../../features/online/domain/catalog.dart';

// An owned subprocess isolates blocking OS-keyring prompts from Flutter and
// audio isolates. Windows uses user-bound DPAPI; Linux uses Secret Service.
// There is deliberately no plaintext or home-grown encryption fallback.
final class OsAccountVault implements AccountVault {
  OsAccountVault(this.directory, {String? executable})
    : executable =
          executable ??
          '${File(Platform.resolvedExecutable).parent.path}${Platform.pathSeparator}bitchord_secrets${Platform.isWindows ? '.exe' : ''}';
  final Directory directory;
  final String executable;
  static const _limit = 256 * 1024;

  @override
  Future<String?> read(RequestScope scope) => _run('read', scope);
  @override
  Future<void> write(String secret, RequestScope scope) async {
    if (utf8.encode(secret).length > _limit ||
        secret.isEmpty ||
        secret.contains('\u0000')) {
      throw const AccountFailure(AccountIssue.corruptStorage);
    }
    await _run('write', scope, input: secret);
  }

  @override
  Future<void> delete(RequestScope scope) async {
    await _run('delete', scope);
  }

  Future<Uint8List> _collect(
    Stream<List<int>> stream,
    int limit,
    Process process,
  ) async {
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in stream) {
      if (bytes.length + chunk.length > limit) {
        process.kill();
        throw const AccountFailure(AccountIssue.operationFailed);
      }
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }

  Future<String?> _run(
    String operation,
    RequestScope scope, {
    String? input,
  }) async {
    scope.check();
    Process? process;
    void Function()? detach;
    try {
      process = await Process.start(executable, [
        operation,
        directory.absolute.path,
        'accounts-v1',
      ], runInShell: false);
      final owned = process;
      detach = scope.onCancel(() {
        owned.kill();
      });
      final output = _collect(owned.stdout, _limit, owned);
      final errors = _collect(owned.stderr, 4096, owned);
      // Start consuming before writing. Secrets never appear in arguments,
      // environment, error text or persisted JSON outside the OS vault.
      if (input != null) owned.stdin.add(utf8.encode(input));
      await owned.stdin.close();
      final result = await Future.wait<Object>([
        owned.exitCode,
        output,
        errors,
      ]).timeout(const Duration(seconds: 45));
      scope.check();
      final status = result[0] as int;
      if (operation == 'read' && status == 3) return null;
      if (status != 0)
        throw const AccountFailure(AccountIssue.storageUnavailable);
      return operation == 'read' ? utf8.decode(result[1] as Uint8List) : null;
    } on ProviderFailure {
      rethrow;
    } on AccountFailure {
      rethrow;
    } catch (_) {
      scope.check();
      throw const AccountFailure(AccountIssue.storageUnavailable);
    } finally {
      detach?.call();
      process?.kill();
    }
  }
}
