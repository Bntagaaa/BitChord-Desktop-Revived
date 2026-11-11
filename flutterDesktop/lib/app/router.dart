import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/shell/presentation/desktop_shell.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(initialLocation: '/listen-now', routes: [
    for (final destination in Destination.values)
      GoRoute(path: '/${destination.id}', builder: (context, state) => DesktopShell(destination: destination)),
  ]);
  ref.onDispose(router.dispose);
  return router;
});

final historyProvider = NotifierProvider<NavigationHistory, List<String>>(NavigationHistory.new);
final class NavigationHistory extends Notifier<List<String>> {
  @override
  List<String> build() => const ['/listen-now'];
  void visit(String path) {
    if (state.last == path) return;
    final next = [...state, path];
    state = List.unmodifiable(next.skip(next.length > 32 ? next.length - 32 : 0));
  }
  String? back() {
    if (state.length < 2) return null;
    state = List.unmodifiable(state.take(state.length - 1));
    return state.last;
  }
}
