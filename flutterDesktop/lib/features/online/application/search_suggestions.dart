import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/catalog.dart';
import '../domain/online_port.dart';
import 'catalog_controller.dart';

final searchSuggestionsProvider =
    NotifierProvider<SearchSuggestions, List<String>>(SearchSuggestions.new);

// Matches DesktopSearchSession's edit/submit/dismiss generation fence. A field
// losing focus does not unmount a suggestion before its mouse-up action.
final class SearchSuggestions extends Notifier<List<String>> {
  Timer? _timer;
  RequestScope? _scope;
  int _generation = 0;
  bool _disposed = false;
  @override
  List<String> build() {
    ref.onDispose(() {
      _disposed = true;
      _timer?.cancel();
      _scope?.cancel();
    });
    return const [];
  }

  void dismiss() {
    ++_generation;
    _timer?.cancel();
    _scope?.cancel();
    state = const [];
  }

  void edit(String query, {required bool youtube}) {
    dismiss();
    final api = ref.read(onlinePortProvider);
    if (query.trim().isEmpty || !youtube || api is! OnlineSuggestions) return;
    final generation = _generation;
    final scope = _scope = RequestScope();
    _timer = Timer(const Duration(milliseconds: 200), () async {
      try {
        final rows = await (api as OnlineSuggestions).suggestions(query, scope);
        if (!_disposed && generation == _generation && !scope.cancelled) {
          state = List.unmodifiable(
            rows
                .where(
                  (r) =>
                      r.trim().isNotEmpty &&
                      r.toLowerCase() != query.toLowerCase(),
                )
                .take(20),
          );
        }
      } catch (_) {
        // Suggestions are optional; a failed lookup never blocks submission.
      } finally {
        scope.cancel();
      }
    });
  }
}
