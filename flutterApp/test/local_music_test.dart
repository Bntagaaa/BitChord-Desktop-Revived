import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:bitchord/data/local_artwork.dart';
import 'package:bitchord/data/local_music.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List _u32(int value) => (ByteData(4)..setUint32(0,value)).buffer.asUint8List();
List<int> _atom(String name, List<int> body) => [..._u32(body.length+8),...ascii.encode(name),...body];
List<int> _sync(int value) => [(value>>21)&127,(value>>14)&127,(value>>7)&127,value&127];

void main() {
  late Directory folder;
  late LocalArtworkCache cache;
  late Uint8List png;
  setUp(() async {
    folder = await Directory.systemTemp.createTemp('bitchord-artwork-');
    cache = LocalArtworkCache('${folder.path}/cache');
    // Actual pinned application artwork; no runtime catalog fixture is introduced.
    png = await File('assets/icons/app_icon.png').readAsBytes();
  });
  tearDown(() async { await folder.delete(recursive: true); });

  test('FLAC PICTURE and M4A covr extract the original bytes', () async {
    final picture = [
      ..._u32(3),..._u32(9),...ascii.encode('image/png'),..._u32(0),
      ..._u32(1),..._u32(1),..._u32(32),..._u32(0),..._u32(png.length),...png,
    ];
    final flac = File('${folder.path}/embedded.flac');
    await flac.writeAsBytes([...ascii.encode('fLaC'),134,
      (picture.length>>16)&255,(picture.length>>8)&255,picture.length&255,...picture]);
    final flacArt = await cache.artwork(flac.path);
    expect(flacArt, isNotNull);
    expect(await File.fromUri(Uri.parse(flacArt!)).readAsBytes(),png);
    final mp4 = File('${folder.path}/embedded.m4a');
    await mp4.writeAsBytes(_atom('moov',_atom('udta',_atom('meta',[
      0,0,0,0,..._atom('ilst',_atom('covr',_atom('data',[0,0,0,14,0,0,0,0,...png]))),
    ]))));
    final mp4Art = await cache.artwork(mp4.path);
    expect(mp4Art,isNotNull);
    expect(await File.fromUri(Uri.parse(mp4Art!)).readAsBytes(),png);
  });
  test('MP3 extended headers and UTF16 APIC descriptions preserve image magic', () async {
    final description = [255,254,...'Cover'.codeUnits.expand((code)=>[code,0]),0,0];
    final payload = [1,...ascii.encode('image/png'),0,3,...description,...png];
    final tag = [
      ..._u32(6),0,0,0,0,0,0,
      ...ascii.encode('APIC'),..._u32(payload.length),0,0,...payload,
    ];
    final mp3 = File('${folder.path}/embedded.mp3');
    await mp3.writeAsBytes([...ascii.encode('ID3'),3,0,64,..._sync(tag.length),...tag]);
    final uri = await cache.artwork(mp3.path);
    expect(uri,isNotNull);
    expect(await File.fromUri(Uri.parse(uri!)).readAsBytes(),png);
  });
  test('a negative cache cannot hide a later case-insensitive folder cover', () async {
    final audio = File('${folder.path}/track.wav');
    await audio.writeAsBytes([0,0,0,0]);
    expect(await cache.artwork(audio.path),isNull);
    await File('${folder.path}/Cover.JPG').writeAsBytes(png);
    final uri = await cache.artwork(audio.path);
    expect(uri,endsWith('.png'),reason:'the image bytes determine the exported type');
    expect(await File.fromUri(Uri.parse(uri!)).readAsBytes(),png);
  });
  test('scanner preserves source filename credits and depth rules', () async {
    expect(LocalMusicScanner.parseName('Artist - Title - Live'),(title:'Title - Live',artist:'Artist'));
    expect(LocalMusicScanner.parseName('Title'),(title:'Title',artist:'On This Computer'));
    await File('${folder.path}/Artist - Title.mp3').writeAsBytes([0]);
    final deep = Directory('${folder.path}/a/b/c/d')..createSync(recursive:true);
    await File('${deep.path}/Too Deep.mp3').writeAsBytes([0]);
    final tracks = await const LocalMusicScanner().scan([folder.path]);
    expect(tracks.map((song)=>song.title),['Title']);
    expect(tracks.single.artist,'Artist');
    expect(tracks.single.id,'local:${tracks.single.localUri!.toFilePath()}');
  });
}
