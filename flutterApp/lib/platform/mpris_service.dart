import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:dbus/dbus.dart';

import '../playback/playback.dart';

const _rootInterface = 'org.mpris.MediaPlayer2';
const _playerInterface = 'org.mpris.MediaPlayer2.Player';

/// Linux media keys, KDE/GNOME media widgets, volume, shuffle, repeat and seeking.
class MprisService extends DBusObject {
  MprisService(this.playback, {required this.raise, required this.quit})
    : super(DBusObjectPath('/org/mpris/MediaPlayer2'));
  final PlaybackController playback;
  final Future<void> Function() raise;
  final Future<void> Function() quit;
  DBusClient? _bus;
  String? _lastState;
  bool _publishing = false;
  bool _closed = false;

  Future<void> start() async {
    final bus = DBusClient.session();
    _bus = bus;
    await bus.registerObject(this);
    await bus.requestName(
      'org.mpris.MediaPlayer2.bitchord_flutter.instance$pid',
    );
    playback.addListener(_update);
    _update();
  }

  DBusObjectPath get _trackPath {
    final song = playback.currentSong;
    if (song == null) {
      return DBusObjectPath('/org/mpris/MediaPlayer2/TrackList/NoTrack');
    }
    final id = sha256
        .convert(utf8.encode(song.queueEntryId ?? song.videoId))
        .toString();
    return DBusObjectPath('/org/mpris/MediaPlayer2/track/t_$id');
  }

  Map<String, DBusValue> _rootProperties() => {
    'CanQuit': const DBusBoolean(true),
    'CanRaise': const DBusBoolean(true),
    'HasTrackList': const DBusBoolean(false),
    'CanSetFullscreen': const DBusBoolean(false),
    'Fullscreen': const DBusBoolean(false),
    'Identity': const DBusString('BitChord Desktop Revived'),
    'DesktopEntry': const DBusString('com.music.bitchord.flutter'),
    'SupportedUriSchemes': DBusArray.string(const []),
    'SupportedMimeTypes': DBusArray.string(const [
      'audio/mpeg',
      'audio/flac',
      'audio/ogg',
      'audio/mp4',
      'video/mp4',
    ]),
  };

  Map<String, DBusValue> _playerProperties() {
    final song = playback.currentSong;
    return {
      'PlaybackStatus': DBusString(
        song == null
            ? 'Stopped'
            : playback.playing
            ? 'Playing'
            : 'Paused',
      ),
      'LoopStatus': DBusString(switch (playback.repeat) {
        PlaybackRepeat.off => 'None',
        PlaybackRepeat.one => 'Track',
        PlaybackRepeat.all => 'Playlist',
      }),
      'Rate': DBusDouble(playback.rate),
      'Shuffle': DBusBoolean(playback.shuffle),
      'Volume': DBusDouble(playback.volume / 100),
      'Position': DBusInt64(playback.position.inMicroseconds),
      'MinimumRate': const DBusDouble(0.5),
      'MaximumRate': const DBusDouble(2),
      'CanGoNext': DBusBoolean(playback.queue.isNotEmpty),
      'CanGoPrevious': DBusBoolean(playback.queue.isNotEmpty),
      'CanPlay': DBusBoolean(song != null),
      'CanPause': DBusBoolean(song != null),
      'CanSeek': DBusBoolean(song != null),
      'CanControl': const DBusBoolean(true),
      'Metadata': DBusDict.stringVariant({
        'mpris:trackid': _trackPath,
        if (song != null) ...{
          'xesam:title': DBusString(song.title),
          'xesam:artist': DBusArray.string([song.artist]),
          if (song.albumName != null)
            'xesam:album': DBusString(song.albumName!),
          if (song.thumbnailUrl != null)
            'mpris:artUrl': DBusString(song.artworkAt(544)!),
          'mpris:length': DBusInt64(playback.duration.inMicroseconds),
          if (!song.isLocal)
            'xesam:url': DBusString(
              'https://music.youtube.com/watch?v=${Uri.encodeQueryComponent(song.videoId)}',
            ),
        },
      }),
    };
  }

  @override
  Future<DBusMethodResponse> getProperty(String interface, String name) async {
    final properties = interface == _rootInterface
        ? _rootProperties()
        : interface == _playerInterface
        ? _playerProperties()
        : null;
    final value = properties?[name];
    return value == null
        ? DBusMethodErrorResponse('org.freedesktop.DBus.Error.UnknownProperty')
        : DBusGetPropertyResponse(value);
  }

  @override
  Future<DBusMethodResponse> getAllProperties(String interface) async =>
      interface == _rootInterface
      ? DBusGetAllPropertiesResponse(_rootProperties())
      : interface == _playerInterface
      ? DBusGetAllPropertiesResponse(_playerProperties())
      : DBusMethodErrorResponse('org.freedesktop.DBus.Error.UnknownInterface');

  @override
  Future<DBusMethodResponse> setProperty(
    String interface,
    String name,
    DBusValue value,
  ) async {
    if (interface != _playerInterface) {
      return DBusMethodErrorResponse(
        'org.freedesktop.DBus.Error.PropertyReadOnly',
      );
    }
    try {
      switch (name) {
        case 'Volume':
          await playback.setVolume((value as DBusDouble).value * 100);
        case 'Shuffle':
          await playback.setShuffle((value as DBusBoolean).value);
        case 'Rate':
          await playback.setRate((value as DBusDouble).value);
        case 'LoopStatus':
          final status = (value as DBusString).value;
          if (!const {'None', 'Track', 'Playlist'}.contains(status)) {
            return DBusMethodErrorResponse(
              'org.freedesktop.DBus.Error.InvalidArgs',
            );
          }
          await playback.setRepeat(
            status == 'Track'
                ? PlaybackRepeat.one
                : status == 'Playlist'
                ? PlaybackRepeat.all
                : PlaybackRepeat.off,
          );
        default:
          return DBusMethodErrorResponse(
            'org.freedesktop.DBus.Error.PropertyReadOnly',
          );
      }
      return DBusMethodSuccessResponse([]);
    } on Object {
      return DBusMethodErrorResponse('org.freedesktop.DBus.Error.InvalidArgs');
    }
  }

  @override
  Future<DBusMethodResponse> handleMethodCall(DBusMethodCall methodCall) async {
    try {
      if (methodCall.interface == _rootInterface) {
        if (methodCall.name == 'Raise') {
          await raise();
        } else if (methodCall.name == 'Quit') {
          unawaited(quit());
        } else {
          return DBusMethodErrorResponse(
            'org.freedesktop.DBus.Error.UnknownMethod',
          );
        }
      } else if (methodCall.interface == _playerInterface) {
        switch (methodCall.name) {
          case 'Play':
            await playback.play();
          case 'Pause':
            await playback.pause();
          case 'PlayPause':
            await playback.playPause();
          case 'Stop':
            await playback.stop();
          case 'Next':
            await playback.next();
          case 'Previous':
            await playback.previous();
          case 'Seek':
            await playback.seek(
              playback.position +
                  Duration(
                    microseconds: (methodCall.values.single as DBusInt64).value,
                  ),
            );
            await emitSignal(_playerInterface, 'Seeked', [
              DBusInt64(playback.position.inMicroseconds),
            ]);
          case 'SetPosition':
            if ((methodCall.values[0] as DBusObjectPath).value ==
                _trackPath.value) {
              await playback.seek(
                Duration(
                  microseconds: (methodCall.values[1] as DBusInt64).value,
                ),
              );
              await emitSignal(_playerInterface, 'Seeked', [
                DBusInt64(playback.position.inMicroseconds),
              ]);
            }
          default:
            return DBusMethodErrorResponse(
              'org.freedesktop.DBus.Error.UnknownMethod',
            );
        }
      } else {
        return DBusMethodErrorResponse(
          'org.freedesktop.DBus.Error.UnknownInterface',
        );
      }
      return DBusMethodSuccessResponse([]);
    } on Object {
      return DBusMethodErrorResponse('org.freedesktop.DBus.Error.Failed');
    }
  }

  void _update() {
    if (_closed || _publishing) return;
    final state =
        '${playback.currentSong?.queueEntryId}:${playback.currentSong?.videoId}:'
        '${playback.playing}:${playback.repeat}:${playback.shuffle}:${playback.volume}:'
        '${playback.rate}:${playback.duration.inSeconds}';
    if (_lastState == state) return;
    _lastState = state;
    _publishing = true;
    final changed = _playerProperties()..remove('Position');
    unawaited(
      emitPropertiesChanged(
        _playerInterface,
        changedProperties: changed,
      ).catchError((Object _) {}).whenComplete(() {
        _publishing = false;
        _update();
      }),
    );
  }

  @override
  List<DBusIntrospectInterface> introspect() => parseDBusIntrospectXml('''
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/><method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/><property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/><property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/><property name="Fullscreen" type="b" access="read"/>
    <property name="CanSetFullscreen" type="b" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/><property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/><method name="Previous"/><method name="Pause"/><method name="PlayPause"/><method name="Stop"/><method name="Play"/>
    <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
    <method name="SetPosition"><arg name="TrackId" type="o" direction="in"/><arg name="Position" type="x" direction="in"/></method>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/><property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/><property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/><property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/><property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/><property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/><property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/><property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>''').interfaces;

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    playback.removeListener(_update);
    await _bus?.close();
    _bus = null;
  }
}
