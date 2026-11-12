import 'dart:convert';
import 'dart:io';
import '../lib/features/online/data/provider_http.dart';
import '../lib/features/online/data/youtube_music.dart';
import '../lib/features/online/domain/catalog.dart';

// Opt-in anonymous live probe. Counts/status only; never save personalized
// catalogs, visitor ids, signed locations, headers, account data or cookies.
Future<void> main() async {
  final provider = YouTubeMusic(const ProviderHttp());
  final scope = RequestScope();
  final report = <String, Object?>{
    'provider': 'youtube',
    'authenticated': false,
  };
  for (final action in ['search', 'home']) {
    try {
      final page = action == 'search'
          ? await provider.search('Beethoven', scope)
          : await provider.browse('FEmusic_home', scope);
      report[action] = {
        'entries': page.entries.length,
        'continuation': page.continuation != null,
      };
      if (page.entries.isEmpty) report['$action-status'] = 'EMPTY_UNVERIFIED';
    } on ProviderFailure catch (e) {
      report[action] = {'failure': e.problem.name, 'status': e.status};
    }
  }
  scope.cancel();
  stdout.writeln(jsonEncode(report));
}
