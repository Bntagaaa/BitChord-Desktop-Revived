import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'bindings.g.dart';

// Used only by the service/storage isolates and standalone integration tests.
final class NativeApi {
  NativeApi(String path)
    : bindings = BitchordBindings(DynamicLibrary.open(path)) {
    final caps = allocate<bc_caps>(sizeOf<bc_caps>());
    try {
      caps.ref.struct_size = sizeOf<bc_caps>();
      check(bindings.bc_get_caps(caps));
      if (caps.ref.abi != BC_ABI ||
          caps.ref.command_size != sizeOf<bc_command>() ||
          caps.ref.snapshot_size != sizeOf<bc_snapshot>() ||
          caps.ref.event_size != sizeOf<bc_event>()) {
        throw StateError('Incompatible native audio ABI');
      }
    } finally {
      free(caps);
    }
  }
  final BitchordBindings bindings;
  static String get bundledLibrary {
    final root = File(Platform.resolvedExecutable).parent.path;
    return Platform.isWindows
        ? '$root/bitchord_audio.dll'
        : '$root/lib/libbitchord_audio.so';
  }

  Pointer<T> allocate<T extends NativeType>(int bytes) {
    final pointer = bindings.bc_alloc(bytes);
    if (pointer == nullptr) throw StateError('Native allocation limit');
    return pointer.cast<T>();
  }

  void free(Pointer pointer) => bindings.bc_free(pointer.cast<Void>());
  static void check(int status) {
    if (status != BC_OK) throw StateError('Native operation failed ($status)');
  }

  Pointer<Char> string(String text) {
    final data = utf8.encode(text);
    if (data.contains(0)) throw const FormatException('Embedded NUL');
    final pointer = allocate<Uint8>(data.length + 1);
    pointer.asTypedList(data.length).setAll(0, data);
    return pointer.cast<Char>();
  }

  static void put(Array<Char> target, String text, int capacity) {
    final data = utf8.encode(text);
    if (data.length >= capacity || data.contains(0))
      throw const FormatException('Native string bound');
    for (var i = 0; i < data.length; i++) {
      target[i] = data[i];
    }
    target[data.length] = 0;
  }

  static String text(Array<Char> source, int capacity) {
    final bytes = <int>[];
    for (var i = 0; i < capacity && source[i] != 0; i++) {
      bytes.add(source[i] & 255);
    }
    return utf8.decode(bytes);
  }

  String digest(List<int> data) {
    final bytes = allocate<Uint8>(data.isEmpty ? 1 : data.length);
    final hash = allocate<Uint8>(32);
    try {
      bytes.asTypedList(data.length).setAll(0, data);
      check(bindings.bc_sha256(bytes, data.length, hash));
      return hash
          .asTypedList(32)
          .map((n) => n.toRadixString(16).padLeft(2, '0'))
          .join();
    } finally {
      free(hash);
      free(bytes);
    }
  }

  (String?, int) readStore(String path) {
    final p = string(path),
        bytes = allocate<Pointer<Uint8>>(sizeOf<Pointer<Uint8>>());
    final length = allocate<Uint64>(8), revision = allocate<Uint64>(8);
    try {
      check(bindings.bc_store_read(p, bytes, length, revision));
      return (
        length.value == 0
            ? null
            : utf8.decode(bytes.value.asTypedList(length.value)),
        revision.value,
      );
    } finally {
      free(bytes.value);
      free(bytes);
      free(length);
      free(revision);
      free(p);
    }
  }

  void writeStore(String path, String json, int expectedRevision) {
    final p = string(path);
    final data = utf8.encode(json),
        bytes = allocate<Uint8>(utf8.encode(json).length);
    try {
      bytes.asTypedList(data.length).setAll(0, data);
      check(bindings.bc_store_write(p, bytes, data.length, expectedRevision));
    } finally {
      free(bytes);
      free(p);
    }
  }
}
