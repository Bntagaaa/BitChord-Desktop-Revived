import 'dart:convert';
import 'package:xml/xml.dart';

String field(String value) =>
    base64Url.encode(utf8.encode(value)).replaceAll('=', '');
String legacySong({String id = 'recording', String title = '日本語 • Déjà vu'}) =>
    [
      id,
      title,
      'Björk',
      'https://example.invalid/art.jpg',
      '3:42',
      'UCartist',
      'MPREalbum',
      'Album',
      'file:///music/one.flac',
      '/music/one.flac',
      'false',
      'true',
      'set-one',
      'true',
      '1700000000',
      '1700000001',
      'LOSSLESS',
      'true',
    ].map(field).join('|');
String xml(Map<String, String> entries) => XmlElement(XmlName('map'), [], [
  for (final entry in entries.entries)
    XmlElement(XmlName('entry'), [
      XmlAttribute(XmlName('key'), entry.key),
      XmlAttribute(XmlName('value'), entry.value),
    ]),
]).toXmlString();
