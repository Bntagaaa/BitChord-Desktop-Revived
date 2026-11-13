import '../../../core/domain/audio_contract.dart';
import 'catalog.dart';
import 'dart:typed_data';

abstract interface class OnlineAccounts {
  bool get signedIn;
  Future<void> signIn(String cookie, RequestScope scope);
  Future<void> signOut();
}

abstract interface class OnlineArtwork {
  Future<Uint8List> artwork(Uri url, RequestScope scope);
}

abstract interface class OnlineSuggestions {
  Future<List<String>> suggestions(String query, RequestScope scope);
}

/// An authenticated account's real library, not a local placeholder.
abstract interface class OnlineLibrary {
  Future<CatalogPage> library(RequestScope scope);
}

abstract interface class OnlinePlaybackIdentity {
  String get currentEntryId;
}

abstract interface class OnlineCapabilities {
  bool get youtubeAudioConfigured;
}

final class DownloadedEntry {
  const DownloadedEntry(this.entry, this.bytes);
  final CatalogEntry entry;
  final int bytes;
}

abstract interface class OnlinePort {
  List<String> get providers;
  String get currentTitle;
  String get currentSubtitle;
  Future<void> connectAddon(String url, RequestScope scope);
  Future<CatalogPage> search(
    String provider,
    String query,
    RequestScope scope, {
    String? continuation,
    SearchFilter filter = SearchFilter.all,
  });
  Future<CatalogPage> browse(
    String id,
    RequestScope scope, {
    String? continuation,
    String? params,
  });
  Future<void> play(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
  );
  Future<void> pause();
  Future<void> resume();
  Future<void> seek(int positionUs);
  Future<PlaybackSnapshot> snapshot();
  Future<void> download(
    CatalogEntry entry,
    AudioQuality quality,
    RequestScope scope,
    void Function(int, int) progress,
  );
  Future<List<DownloadedEntry>> downloads();
  Future<void> playOffline(CatalogEntry entry, RequestScope scope);
  Future<void> removeDownload(CatalogEntry entry);
}
