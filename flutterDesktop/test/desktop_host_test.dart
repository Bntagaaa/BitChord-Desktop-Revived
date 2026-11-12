import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/core/platform/desktop_host.dart';
import 'package:bitchord_desktop/core/platform/diagnostics.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('window_manager');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('Native close waits for settings drain and ignores repeated requests', () async {
    final drain = Completer<void>();
    final host = DesktopHost(Diagnostics())..beforeClose = () => drain.future;
    final closing = host.close();
    await host.close();
    expect(calls, isEmpty);
    drain.complete();
    await closing;
    expect(calls.map((call) => call.method), ['setPreventClose', 'close']);
    expect(calls.first.arguments, {'isPreventClose': false});
    await host.close();
    expect(calls.length, 2);
  });

  test('A failed settings drain is reported and still permits native close', () async {
    final diagnostics = Diagnostics();
    final host = DesktopHost(diagnostics)
      ..beforeClose = () async => throw StateError('test-owned drain failure');
    await host.close();
    expect(diagnostics.records, ['shutdown: StateError']);
    expect(calls.map((call) => call.method), ['setPreventClose', 'close']);
    expect(calls.first.arguments, {'isPreventClose': false});
  });
}
