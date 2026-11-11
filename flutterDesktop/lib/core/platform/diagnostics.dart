import 'dart:collection';
import 'dart:io';
import '../domain/failure_reporter.dart';

final class Diagnostics implements FailureReporter {
  final Queue<String> _records = Queue<String>();
  List<String> get records => List.unmodifiable(_records);

  @override
  void failure(String operation, Object error) {
    // Do not stringify external exceptions: they may contain headers or URLs.
    final message = '$operation: ${error.runtimeType}';
    if (_records.length == 64) _records.removeFirst();
    _records.addLast(message);
    stderr.writeln(message);
  }
}
