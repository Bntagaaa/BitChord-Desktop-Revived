final class CollectionActions {
  const CollectionActions({this.playlistId, this.owned, this.saved, this.channelId, this.subscribed, this.description});
  final String? playlistId, channelId, description;
  final bool? owned, saved, subscribed;
}

final class LibraryWriteUnconfirmed implements Exception {
  const LibraryWriteUnconfirmed();
  @override
  String toString() => 'The server did not confirm this change. Refresh the library before retrying; the request was not automatically repeated.';
}
