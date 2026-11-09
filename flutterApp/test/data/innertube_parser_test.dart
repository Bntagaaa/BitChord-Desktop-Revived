import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/domain/models.dart';
import 'package:bitchord_desktop/data/innertube_parser.dart';

JsonMap text(String value) => {
  'runs': [
    {'text': value},
  ],
};
JsonMap row(String id, {String? setId, bool video = false}) => {
  'flexColumns': [
    {
      'musicResponsiveListItemFlexColumnRenderer': {'text': text('Recording')},
    },
    {
      'musicResponsiveListItemFlexColumnRenderer': {
        'text': {
          'runs': [
            {'text': video ? 'Video • ' : 'Song • '},
            {
              'text': 'Artist',
              'navigationEndpoint': {
                'browseEndpoint': {'browseId': 'UCartist'},
              },
            },
            {'text': ' • '},
            {
              'text': 'Album',
              'navigationEndpoint': {
                'browseEndpoint': {'browseId': 'MPREalbum'},
              },
            },
          ],
        },
      },
    },
  ],
  'fixedColumns': [
    {
      'musicResponsiveListItemFixedColumnRenderer': {'text': text('3:42')},
    },
  ],
  'playlistItemData': {'videoId': id, 'playlistSetVideoId': ?setId},
  'navigationEndpoint': {
    'watchEndpoint': {
      'videoId': id,
      'watchEndpointMusicSupportedConfigs': {
        'watchEndpointMusicConfig': {
          'musicVideoType': video
              ? 'MUSIC_VIDEO_TYPE_OMV'
              : 'MUSIC_VIDEO_TYPE_ATV',
        },
      },
    },
  },
};

JsonMap panel(String id, {required bool video}) => {
  'playlistPanelVideoRenderer': {
    'videoId': id,
    'title': text('Recording'),
    'longBylineText': text('Artist • Album'),
    'lengthText': text('3:42'),
    'navigationEndpoint': {
      'watchEndpoint': {
        'videoId': id,
        'watchEndpointMusicSupportedConfigs': {
          'watchEndpointMusicConfig': {
            'musicVideoType': video
                ? 'MUSIC_VIDEO_TYPE_OMV'
                : 'MUSIC_VIDEO_TYPE_ATV',
          },
        },
      },
    },
  },
};

void main() {
  test('playlist ownership uses its header, not a track delete action', () {
    final saved = <String, dynamic>{
      'header': {
        'musicResponsiveHeaderRenderer': {
          'title': text('Someone else’s playlist'),
          'buttons': [
            {
              'toggleButtonRenderer': {
                'defaultIcon': {'iconType': 'BOOKMARK_BORDER'},
                'isToggled': true,
              },
            },
          ],
        },
      },
      'rowMenu': {
        'menuNavigationItemRenderer': {
          'icon': {'iconType': 'DELETE'},
        },
      },
    };
    expect(InnertubeParser.playlistOwned(saved), isFalse);
    expect(
      InnertubeParser.playlistOwned({
        'header': {
          'musicEditablePlaylistDetailHeaderRenderer': {
            'header': saved['header'],
          },
        },
      }),
      isTrue,
    );
    expect(InnertubeParser.playlistOwned({'continuationContents': {}}), isNull);
    expect(
      InnertubeParser.playlistOwned({
        'header': {
          'musicResponsiveHeaderRenderer': {'buttons': []},
        },
      }),
      isTrue,
    );
  });

  test(
    'an album browse target is not converted into its play overlay song',
    () {
      final album = row('overlay-audio')
        ..['navigationEndpoint'] = {
          'browseEndpoint': {'browseId': 'MPREalbum'},
        }
        ..['overlay'] = {
          'musicItemThumbnailOverlayRenderer': {
            'content': {
              'musicPlayButtonRenderer': {
                'playNavigationEndpoint': {
                  'watchEndpoint': {'videoId': 'overlay-audio'},
                },
              },
            },
          },
        };
      final page = InnertubeParser.parseSearch({
        'contents': [
          {'musicResponsiveListItemRenderer': album},
        ],
      });
      expect(page.songs, isEmpty);
      expect(page.results.single.item?.type, BrowseType.album);
      expect(page.results.single.item?.browseId, 'MPREalbum');
    },
  );

  test(
    'playlist rows preserve duplicate occurrences and exclude suggestions',
    () {
      final page = InnertubeParser.parseDetail({
        'header': {
          'musicDetailHeaderRenderer': {'title': text('Playlist')},
        },
        'contents': [
          {
            'musicPlaylistShelfRenderer': {
              'contents': [
                {
                  'musicResponsiveListItemRenderer': row(
                    'same',
                    setId: 'set-one',
                  ),
                },
                {
                  'musicResponsiveListItemRenderer': row(
                    'same',
                    setId: 'set-two',
                  ),
                },
                {
                  'continuationItemRenderer': {
                    'continuationEndpoint': {
                      'continuationCommand': {'token': 'next-page'},
                    },
                  },
                },
              ],
            },
          },
          {
            'musicShelfRenderer': {
              'title': text('Suggestions'),
              'contents': [
                {'musicResponsiveListItemRenderer': row('suggested')},
              ],
            },
          },
        ],
      }, 'VLplaylist');
      expect(page.title, 'Playlist');
      expect(page.songs.map((s) => s.videoId), ['same', 'same']);
      expect(page.songs.map((s) => s.setVideoId), ['set-one', 'set-two']);
      expect(
        page.songs.first.duration,
        const Duration(minutes: 3, seconds: 42),
      );
      expect(page.songs.first.albumId, 'MPREalbum');
      expect(page.songs.first.artistId, 'UCartist');
      expect(page.suggestedSongs.single.videoId, 'suggested');
      expect(page.continuation, 'next-page');
    },
  );

  test(
    'paired watch renderer contributes exactly the chosen audio or video half',
    () {
      final raw = <String, dynamic>{
        'contents': [
          {
            'playlistPanelVideoWrapperRenderer': {
              'primaryRenderer': panel('audio', video: false),
              'counterpart': [
                {'counterpartRenderer': panel('video', video: true)},
              ],
            },
          },
          panel('next-audio', video: false),
        ],
      };
      expect(InnertubeParser.parseWatchQueue(raw).map((s) => s.videoId), [
        'audio',
        'next-audio',
      ]);
      expect(
        InnertubeParser.parseWatchQueue(
          raw,
          preferVideo: true,
        ).map((s) => s.videoId),
        ['video', 'next-audio'],
      );
      expect(
        InnertubeParser.parseWatchQueue(raw, preferVideo: true).first.isVideo,
        isTrue,
      );
    },
  );

  test(
    'videos stay in the video tab and repeated renderer cards deduplicate',
    () {
      final raw = <String, dynamic>{
        'contents': [
          {'musicResponsiveListItemRenderer': row('audio')},
          {'musicResponsiveListItemRenderer': row('audio')},
          {'musicResponsiveListItemRenderer': row('video', video: true)},
        ],
      };
      expect(InnertubeParser.parseSearch(raw).songs.map((s) => s.videoId), [
        'audio',
      ]);
      expect(
        InnertubeParser.parseSearch(
          raw,
          filter: SearchFilter.videos,
        ).songs.map((s) => s.videoId),
        ['video'],
      );
    },
  );

  test('both continuation renderer generations are accepted', () {
    expect(
      InnertubeParser.continuation({
        'continuations': [
          {
            'nextContinuationData': {'continuation': 'old-token'},
          },
        ],
      }),
      'old-token',
    );
    expect(
      InnertubeParser.parseDetail({
        'continuationContents': {
          'musicPlaylistShelfContinuation': {
            'contents': [
              {'musicResponsiveListItemRenderer': row('continued')},
            ],
          },
        },
      }, 'VLplaylist').songs.single.videoId,
      'continued',
    );
  });
}
