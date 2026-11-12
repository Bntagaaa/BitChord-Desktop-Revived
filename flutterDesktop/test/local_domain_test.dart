import 'package:flutter_test/flutter_test.dart';
import '../tool/phase3_check.dart' show queueCorpus;
import '../lib/features/local_data/domain/local_state.dart';
import '../lib/features/playback/domain/local_queue.dart';

void main() {
  test(
    '450 executed pinned Kotlin queue transitions and migration encoder',
    queueCorpus,
  );
  test(
    'versioned settings and queue identity validation reject corrupt data',
    () {
      expect(
        () => LocalSettings.fromJson({'version': 99}),
        throwsFormatException,
      );
      final row = QueueEntry(
        entryId: 'same',
        catalogId: 'track',
        renditionId: 'recording',
        sourceId: 'local',
        title: '',
      );
      expect(() => LocalQueue([row, row]), throwsArgumentError);
      expect(() => LocalQueue([row], cursor: 1), throwsArgumentError);
    },
  );
}
