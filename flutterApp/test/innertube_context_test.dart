import 'dart:convert';
import 'package:bitchord/data/innertube.dart';
import 'package:bitchord/domain/music.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('WEB_REMIX headers follow the source and a signed-out shell cannot scope an account', () async {
    http.Request? sent;
    final api = InnerTube(client: MockClient((request) async {
      if (request.method == 'GET') {
        return http.Response('''{"LOGGED_IN":false,"DATASYNC_ID":"not-an-account", 
          "VISITOR_DATA":"test-visitor","INNERTUBE_CLIENT_VERSION":"1.server"}''', 200);
      }
      sent = request;
      return http.Response('{}', 200);
    }));
    api.language = 'in';
    api.cookie = 'SAPISID=test-only';
    await api.search('provided query', SearchFilter.songs);
    final request = sent!;
    expect(request.headers['user-agent'], contains('Chrome/141.0.0.0'));
    expect(request.headers['accept-language'], 'id,en-US;q=0.8,en;q=0.7');
    expect(request.headers['x-youtube-client-version'], '1.server');
    expect(request.headers['x-goog-visitor-id'], 'test-visitor');
    expect(request.headers['x-goog-pageid'], isNull);
    final body = jsonDecode(request.body) as Map<String, dynamic>;
    final context = body['context'] as Map<String, dynamic>;
    expect((context['client'] as Map<String, dynamic>)['hl'], 'id');
    expect((context['user'] as Map<String, dynamic>)['onBehalfOfUser'], isNull);
    api.close();
  });
}
