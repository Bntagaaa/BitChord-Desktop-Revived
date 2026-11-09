import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import 'package:bitchord/platform/audio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:media_kit/media_kit.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('native decoder advances and seeks through a real PCM file', (
    tester,
  ) async {
    MediaKit.ensureInitialized();
    final temporary = await Directory.systemTemp.createTemp(
      'bitchord-native-audio-',
    );
    final file = File('${temporary.path}/tone.wav');
    const rate = 48000;
    const frames = rate * 6;
    final bytes = ByteData(44 + frames * 4);
    void ascii(int offset, String value) {
      for (var i = 0; i < value.length; i++) {
        bytes.setUint8(offset + i, value.codeUnitAt(i));
      }
    }

    ascii(0, 'RIFF');
    bytes.setUint32(4, bytes.lengthInBytes - 8, Endian.little);
    ascii(8, 'WAVE');
    ascii(12, 'fmt ');
    bytes.setUint32(16, 16, Endian.little);
    bytes.setUint16(20, 1, Endian.little);
    bytes.setUint16(22, 2, Endian.little);
    bytes.setUint32(24, rate, Endian.little);
    bytes.setUint32(28, rate * 4, Endian.little);
    bytes.setUint16(32, 4, Endian.little);
    bytes.setUint16(34, 16, Endian.little);
    ascii(36, 'data');
    bytes.setUint32(40, frames * 4, Endian.little);
    for (var frame = 0; frame < frames; frame++) {
      final value = (sin(2 * pi * 440 * frame / rate) * 1600).round();
      bytes.setInt16(44 + frame * 4, value, Endian.little);
      bytes.setInt16(46 + frame * 4, value, Endian.little);
    }
    await file.writeAsBytes(bytes.buffer.asUint8List(), flush: true);
    final audio = NativeAudioOutput();
    var position = Duration.zero;
    var duration = Duration.zero;
    final errors = <String>[];
    final subscriptions = [
      audio.position.listen((value) => position = value),
      audio.duration.listen((value) => duration = value),
      audio.errors.listen(errors.add),
    ];
    try {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: Text('Native audio integration')),
        ),
      );
      await audio.open(file.uri);
      final deadline = DateTime.now().add(const Duration(seconds: 20));
      while (position < const Duration(milliseconds: 200) &&
          DateTime.now().isBefore(deadline)) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(errors, isEmpty);
      expect(duration.inMilliseconds, closeTo(6000, 100));
      expect(position, greaterThan(const Duration(milliseconds: 100)));
      await audio.seek(const Duration(seconds: 3));
      final seekDeadline = DateTime.now().add(const Duration(seconds: 5));
      while (position < const Duration(seconds: 3) &&
          DateTime.now().isBefore(seekDeadline)) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(position, greaterThanOrEqualTo(const Duration(seconds: 3)));
      await audio.setVolume(.5);
      await audio.playPause();
    } finally {
      for (final subscription in subscriptions) {
        await subscription.cancel();
      }
      await audio.close();
      await temporary.delete(recursive: true);
    }
  });
}
