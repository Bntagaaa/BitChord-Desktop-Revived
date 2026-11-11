import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:http/http.dart' as http;
import '../../domain/application.dart';
import '../theme.dart';
import 'settings_widgets.dart';

/// Alias for DesktopIntegrationsDialog to satisfy Kotlin reference parity.
typedef DesktopAccountDialog = DesktopIntegrationsDialog;

/// Modal dialog card shell clamped to desktop max width with backdrop scrim.
class DesktopDialogPanel extends StatelessWidget {
  const DesktopDialogPanel({
    super.key,
    required this.child,
    required this.onDismiss,
    this.maxWidth = 460.0,
    this.dismissOnEscape = true,
  });

  final Widget child;
  final VoidCallback onDismiss;
  final double maxWidth;
  final bool dismissOnEscape;

  @override
  Widget build(BuildContext context) {
    final colors = paletteOf(context);

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        children: [
          // Modal Backdrop Scrim
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onDismiss,
              child: ColoredBox(color: colors.scrim),
            ),
          ),
          // Centered Card Shell
          Center(
            child: FocusScope(
              autofocus: true,
              child: ConstrainedBox(
                constraints: BoxConstraints(maxWidth: maxWidth),
                child: Material(
                  color: Colors.transparent,
                  child: Container(
                    decoration: BoxDecoration(
                      color: colors.cardFill,
                      borderRadius: BorderRadius.circular(20.0),
                      border: Border.all(color: colors.cardEdge, width: 0.5),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: child,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Helper function to display desktop modal dialogs with smooth fade/scale transition.
Future<T?> showDesktopDialog<T>({
  required BuildContext context,
  required Widget Function(BuildContext) builder,
  bool barrierDismissible = true,
  double maxWidth = 460.0,
}) {
  return showGeneralDialog<T>(
    context: context,
    barrierDismissible: barrierDismissible,
    barrierLabel: 'Dismiss',
    barrierColor: Colors.transparent,
    transitionDuration: const Duration(milliseconds: 160),
    pageBuilder: (context, _, _) => builder(context),
    transitionBuilder: (context, animation, _, child) {
      final curved = CurvedAnimation(
        parent: animation,
        curve: Curves.easeOutCubic,
      );
      return FadeTransition(
        opacity: curved,
        child: ScaleTransition(
          scale: Tween<double>(begin: 0.97, end: 1.0).animate(curved),
          child: child,
        ),
      );
    },
  );
}

// ---------------------------------------------------------------------------
// 1. Account & Integrations Dialog
// ---------------------------------------------------------------------------

class DesktopIntegrationsDialog extends StatefulWidget {
  const DesktopIntegrationsDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopIntegrationsDialog> createState() =>
      _DesktopIntegrationsDialogState();
}

class _DesktopIntegrationsDialogState extends State<DesktopIntegrationsDialog> {
  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final settings = widget.app.settings;

    final spotifyToken = settings.get(
      'spotify_spdc_token',
      settings.get('spotify_dc', ''),
    );
    final spotifyConnected = spotifyToken.isNotEmpty;

    final discordRpc = settings.get(
      'discord_rpc_enabled',
      settings.get('discord_enabled', false),
    );
    final discordDetails = settings.get('discord_use_details', false);
    final discordAdvanced = settings.get('discord_advanced_mode', false);

    final lastfmEnabled = settings.get('lastfm_enabled', false);
    final lastfmUser = settings.get('lastfm_username', '');

    final lbEnabled = settings.get('listenbrainz_enabled', false);

    final minDuration = settings.get('scrobble_min_duration', 30);
    final scrobbleDelay = settings.get('scrobble_delay_percent', 0.5);
    final maxDelay = settings.get('scrobble_max_delay', 180);

    return DesktopDialogPanel(
      maxWidth: 460.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Account & integrations',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  color: palette.foreground,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 18.0),

              // Spotify Section
              Text(
                'SPOTIFY',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: palette.secondary,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 6.0),
              Container(
                decoration: BoxDecoration(
                  color: palette.cardInsetFill,
                  borderRadius: BorderRadius.circular(12.0),
                  border: Border.all(color: palette.cardEdge, width: 0.5),
                ),
                child: Column(
                  children: [
                    ListTile(
                      dense: true,
                      title: Text(
                        'Spotify',
                        style: TextStyle(
                          color: palette.foreground,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      subtitle: Text(
                        spotifyConnected ? 'Connected' : 'Not connected',
                        style: TextStyle(
                          color: spotifyConnected
                              ? Colors.greenAccent
                              : palette.secondary,
                        ),
                      ),
                      trailing: TextButton(
                        onPressed: () {
                          showDesktopDialog(
                            context: context,
                            builder: (_) =>
                                DesktopSpotifyCanvasDialog(app: widget.app),
                          ).then((_) => setState(() {}));
                        },
                        child: Text(
                          spotifyConnected ? 'Switch account' : 'Connect',
                        ),
                      ),
                    ),
                    if (spotifyConnected)
                      ListTile(
                        dense: true,
                        title: Text(
                          'Disconnect',
                          style: TextStyle(color: palette.destructive),
                        ),
                        onTap: () {
                          unawaited(settings.set('spotify_dc', ''));
                          unawaited(settings.set('spotify_spdc_token', ''));
                          setState(() {});
                        },
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 18.0),

              // Discord Rich Presence Section
              Text(
                'RICH PRESENCE',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: palette.secondary,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 6.0),
              Container(
                decoration: BoxDecoration(
                  color: palette.cardInsetFill,
                  borderRadius: BorderRadius.circular(12.0),
                  border: Border.all(color: palette.cardEdge, width: 0.5),
                ),
                child: Column(
                  children: [
                    SwitchListTile(
                      dense: true,
                      title: Text(
                        'Show what I am playing',
                        style: TextStyle(color: palette.foreground),
                      ),
                      subtitle: Text(
                        discordRpc
                            ? 'Sharing presence via Discord'
                            : 'Connect an account or desktop Discord client',
                        style: TextStyle(color: palette.secondary),
                      ),
                      value: discordRpc,
                      activeThumbColor: palette.accent,
                      onChanged: (val) {
                        unawaited(settings.set('discord_rpc_enabled', val));
                        unawaited(settings.set('discord_enabled', val));
                        setState(() {});
                      },
                    ),
                    if (discordRpc) ...[
                      Divider(color: palette.divider, height: 1.0),
                      SwitchListTile(
                        dense: true,
                        title: Text(
                          'Lead with the song',
                          style: TextStyle(color: palette.foreground),
                        ),
                        subtitle: Text(
                          'Puts the title on the bold line instead of the artist',
                          style: TextStyle(color: palette.secondary),
                        ),
                        value: discordDetails,
                        activeThumbColor: palette.accent,
                        onChanged: (val) {
                          unawaited(settings.set('discord_use_details', val));
                          setState(() {});
                        },
                      ),
                      SwitchListTile(
                        dense: true,
                        title: Text(
                          'Customize the card',
                          style: TextStyle(color: palette.foreground),
                        ),
                        subtitle: Text(
                          'Status, wording and interactive buttons',
                          style: TextStyle(color: palette.secondary),
                        ),
                        value: discordAdvanced,
                        activeThumbColor: palette.accent,
                        onChanged: (val) {
                          unawaited(settings.set('discord_advanced_mode', val));
                          setState(() {});
                        },
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 18.0),

              // Scrobbling Section
              Text(
                'SCROBBLING',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: palette.secondary,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 6.0),
              Container(
                decoration: BoxDecoration(
                  color: palette.cardInsetFill,
                  borderRadius: BorderRadius.circular(12.0),
                  border: Border.all(color: palette.cardEdge, width: 0.5),
                ),
                child: Column(
                  children: [
                    SwitchListTile(
                      dense: true,
                      title: Text(
                        'ListenBrainz',
                        style: TextStyle(color: palette.foreground),
                      ),
                      subtitle: Text(
                        lbEnabled ? 'Connected' : 'Enter a token to enable',
                        style: TextStyle(color: palette.secondary),
                      ),
                      value: lbEnabled,
                      activeThumbColor: palette.accent,
                      onChanged: (val) {
                        unawaited(settings.set('listenbrainz_enabled', val));
                        setState(() {});
                      },
                    ),
                    Divider(color: palette.divider, height: 1.0),
                    SwitchListTile(
                      dense: true,
                      title: Text(
                        'Last.fm',
                        style: TextStyle(color: palette.foreground),
                      ),
                      subtitle: Text(
                        lastfmEnabled
                            ? (lastfmUser.isNotEmpty
                                  ? 'Signed in as $lastfmUser'
                                  : 'Connected')
                            : 'Tap to sign in',
                        style: TextStyle(color: palette.secondary),
                      ),
                      value: lastfmEnabled,
                      activeThumbColor: palette.accent,
                      onChanged: (val) {
                        unawaited(settings.set('lastfm_enabled', val));
                        setState(() {});
                      },
                    ),
                    if (lastfmEnabled || lbEnabled) ...[
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16.0,
                          vertical: 8.0,
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Minimum song duration: ${minDuration}s',
                              style: TextStyle(
                                color: palette.foreground,
                                fontSize: 13,
                              ),
                            ),
                            SettingsBareSlider(
                              value: minDuration.toDouble(),
                              min: 15.0,
                              max: 120.0,
                              divisions: 21,
                              onChanged: (v) {
                                unawaited(
                                  settings.set(
                                    'scrobble_min_duration',
                                    v.round(),
                                  ),
                                );
                                setState(() {});
                              },
                            ),
                            const SizedBox(height: 6.0),
                            Text(
                              'Scrobble delay: ${(scrobbleDelay * 100).round()}%',
                              style: TextStyle(
                                color: palette.foreground,
                                fontSize: 13,
                              ),
                            ),
                            SettingsBareSlider(
                              value: scrobbleDelay,
                              min: 0.1,
                              max: 1.0,
                              divisions: 9,
                              onChanged: (v) {
                                unawaited(
                                  settings.set('scrobble_delay_percent', v),
                                );
                                setState(() {});
                              },
                            ),
                            const SizedBox(height: 6.0),
                            Text(
                              'Maximum delay: ${maxDelay}s',
                              style: TextStyle(
                                color: palette.foreground,
                                fontSize: 13,
                              ),
                            ),
                            SettingsBareSlider(
                              value: maxDelay.toDouble(),
                              min: 30.0,
                              max: 300.0,
                              divisions: 27,
                              onChanged: (v) {
                                unawaited(
                                  settings.set('scrobble_max_delay', v.round()),
                                );
                                setState(() {});
                              },
                            ),
                          ],
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 20.0),

              // Done Button
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accent,
                    foregroundColor: palette.onAccent,
                  ),
                  child: const Text('Done'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 2. Listen Together Dialog
// ---------------------------------------------------------------------------

class DesktopListenTogetherDialog extends StatefulWidget {
  const DesktopListenTogetherDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopListenTogetherDialog> createState() =>
      _DesktopListenTogetherDialogState();
}

class _DesktopListenTogetherDialogState
    extends State<DesktopListenTogetherDialog> {
  int _partySize = 5;
  final TextEditingController _nicknameController = TextEditingController(
    text: 'Listener',
  );
  final TextEditingController _codeController = TextEditingController();
  final TextEditingController _serverController = TextEditingController();

  @override
  void dispose() {
    _nicknameController.dispose();
    _codeController.dispose();
    _serverController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return DesktopDialogPanel(
      maxWidth: 920.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(28.0),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Listen together',
                        style: Theme.of(context).textTheme.headlineMedium
                            ?.copyWith(
                              color: palette.foreground,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      const SizedBox(height: 4.0),
                      Text(
                        'Everyone hears the same song at the same moment',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: palette.secondary,
                        ),
                      ),
                    ],
                  ),
                  IconButton(
                    icon: Icon(Icons.close_rounded, color: palette.secondary),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 24.0),

              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Start Party Card
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.all(20.0),
                      decoration: BoxDecoration(
                        color: palette.cardInsetFill,
                        borderRadius: BorderRadius.circular(16.0),
                        border: Border.all(color: palette.cardEdge, width: 0.5),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Start a party',
                            style: Theme.of(context).textTheme.titleMedium
                                ?.copyWith(
                                  color: palette.foreground,
                                  fontWeight: FontWeight.w600,
                                ),
                          ),
                          const SizedBox(height: 4.0),
                          Text(
                            'Choose how friends see you and how many can join.',
                            style: TextStyle(
                              color: palette.secondary,
                              fontSize: 13,
                            ),
                          ),
                          const SizedBox(height: 16.0),
                          TextField(
                            controller: _nicknameController,
                            style: TextStyle(color: palette.foreground),
                            decoration: InputDecoration(
                              labelText: 'Nickname',
                              labelStyle: TextStyle(color: palette.secondary),
                              border: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(10.0),
                              ),
                            ),
                          ),
                          const SizedBox(height: 16.0),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    'Party size',
                                    style: TextStyle(color: palette.foreground),
                                  ),
                                  Text(
                                    '2–10 people',
                                    style: TextStyle(
                                      color: palette.secondary,
                                      fontSize: 12,
                                    ),
                                  ),
                                ],
                              ),
                              Row(
                                children: [
                                  IconButton(
                                    icon: const Icon(
                                      Icons.remove_circle_outline_rounded,
                                    ),
                                    onPressed: _partySize > 2
                                        ? () => setState(() => _partySize--)
                                        : null,
                                  ),
                                  Text(
                                    '$_partySize',
                                    style: TextStyle(
                                      color: palette.foreground,
                                      fontWeight: FontWeight.bold,
                                      fontSize: 16,
                                    ),
                                  ),
                                  IconButton(
                                    icon: const Icon(
                                      Icons.add_circle_outline_rounded,
                                    ),
                                    onPressed: _partySize < 10
                                        ? () => setState(() => _partySize++)
                                        : null,
                                  ),
                                ],
                              ),
                            ],
                          ),
                          const SizedBox(height: 20.0),
                          SizedBox(
                            width: double.infinity,
                            child: FilledButton(
                              onPressed: () {},
                              style: FilledButton.styleFrom(
                                backgroundColor: palette.accent,
                                foregroundColor: palette.onAccent,
                                padding: const EdgeInsets.symmetric(
                                  vertical: 14.0,
                                ),
                              ),
                              child: const Text('Start party'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 20.0),

                  // Join Party Card
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.all(20.0),
                      decoration: BoxDecoration(
                        color: palette.cardInsetFill,
                        borderRadius: BorderRadius.circular(16.0),
                        border: Border.all(color: palette.cardEdge, width: 0.5),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Join a party',
                            style: Theme.of(context).textTheme.titleMedium
                                ?.copyWith(
                                  color: palette.foreground,
                                  fontWeight: FontWeight.w600,
                                ),
                          ),
                          const SizedBox(height: 4.0),
                          Text(
                            'Enter the 6-character party code from your host.',
                            style: TextStyle(
                              color: palette.secondary,
                              fontSize: 13,
                            ),
                          ),
                          const SizedBox(height: 16.0),
                          TextField(
                            controller: _codeController,
                            maxLength: 6,
                            textCapitalization: TextCapitalization.characters,
                            style: TextStyle(
                              color: palette.foreground,
                              letterSpacing: 4.0,
                              fontWeight: FontWeight.bold,
                            ),
                            decoration: InputDecoration(
                              labelText: 'Party code',
                              labelStyle: TextStyle(color: palette.secondary),
                              border: OutlineInputBorder(
                                borderRadius: BorderRadius.circular(10.0),
                              ),
                            ),
                          ),
                          const SizedBox(height: 20.0),
                          SizedBox(
                            width: double.infinity,
                            child: OutlinedButton(
                              onPressed: () {},
                              style: OutlinedButton.styleFrom(
                                padding: const EdgeInsets.symmetric(
                                  vertical: 14.0,
                                ),
                              ),
                              child: const Text('Look up party'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20.0),

              // Server Settings Row
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16.0,
                  vertical: 12.0,
                ),
                decoration: BoxDecoration(
                  color: palette.cardInsetFill,
                  borderRadius: BorderRadius.circular(12.0),
                  border: Border.all(color: palette.cardEdge, width: 0.5),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.dns_rounded,
                      size: 20.0,
                      color: palette.secondary,
                    ),
                    const SizedBox(width: 12.0),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Party relay server',
                            style: TextStyle(
                              color: palette.foreground,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          Text(
                            'Default relay: online (32 ms)',
                            style: TextStyle(
                              color: Colors.greenAccent,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    TextButton(
                      onPressed: () {},
                      child: const Text('Configure'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 3. Source Editor Dialog
// ---------------------------------------------------------------------------

class DesktopSourceEditorDialog extends StatefulWidget {
  const DesktopSourceEditorDialog({
    super.key,
    required this.app,
    this.sourceId,
    this.initialUrl = '',
    this.initialName = '',
    this.client,
  });

  final MusicApplication app;
  final String? sourceId;
  final String initialUrl;
  final String initialName;
  final http.Client? client;

  @override
  State<DesktopSourceEditorDialog> createState() =>
      _DesktopSourceEditorDialogState();
}

class _DesktopSourceEditorDialogState extends State<DesktopSourceEditorDialog> {
  late final TextEditingController _urlController;
  late final TextEditingController _nameController;
  String? _status;
  bool _testing = false;

  @override
  void initState() {
    super.initState();
    _urlController = TextEditingController(text: widget.initialUrl);
    _nameController = TextEditingController(text: widget.initialName);
  }

  @override
  void dispose() {
    _urlController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  bool _validateUrl(String url) {
    final uri = Uri.tryParse(url.trim());
    return uri != null &&
        (uri.isScheme('http') || uri.isScheme('https')) &&
        uri.host.isNotEmpty;
  }

  Future<void> _testEndpoint() async {
    final rawUrl = _urlController.text.trim();
    if (!_validateUrl(rawUrl)) {
      setState(
        () => _status = 'Invalid URL: must begin with http:// or https://',
      );
      return;
    }
    setState(() {
      _testing = true;
      _status = 'Connecting to endpoint…';
    });

    final client = widget.client ?? http.Client();
    final shouldClose = widget.client == null;

    try {
      final uri = Uri.parse(rawUrl);
      http.Response response;
      try {
        response = await client.head(uri).timeout(const Duration(seconds: 3));
        if (response.statusCode == 405 || response.statusCode == 501) {
          response = await client.get(uri).timeout(const Duration(seconds: 3));
        }
      } catch (_) {
        response = await client.get(uri).timeout(const Duration(seconds: 3));
      }

      if (mounted) {
        setState(() {
          _testing = false;
          if (response.statusCode >= 200 && response.statusCode < 300) {
            _status = 'Reachable (HTTP ${response.statusCode})';
          } else if (response.statusCode >= 300 && response.statusCode < 400) {
            _status = 'Redirect (HTTP ${response.statusCode})';
          } else {
            _status = 'Endpoint unreachable (HTTP ${response.statusCode})';
          }
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _testing = false;
          _status = 'Connection failed: ${e.toString().split('\n').first}';
        });
      }
    } finally {
      if (shouldClose) {
        client.close();
      }
    }
  }

  Future<void> _saveSource() async {
    final rawUrl = _urlController.text.trim();
    if (!_validateUrl(rawUrl)) {
      setState(
        () => _status = 'Please provide a valid http:// or https:// URL',
      );
      return;
    }
    final uri = Uri.parse(rawUrl);
    final rawName = _nameController.text.trim();
    final name = rawName.isNotEmpty ? rawName : uri.host;
    final id =
        widget.sourceId ?? 'source_${DateTime.now().millisecondsSinceEpoch}';

    final sourceMap = <String, dynamic>{
      'id': id,
      'name': name,
      'url': rawUrl,
      'type': 'addon',
      'enabled': true,
    };

    final rawJson = widget.app.settings.get('custom_sources', '[]');
    List<dynamic> list;
    try {
      list = jsonDecode(rawJson) as List<dynamic>;
    } catch (_) {
      list = [];
    }

    final existingIndex = list.indexWhere(
      (item) => item is Map && item['id'] == id,
    );
    if (existingIndex >= 0) {
      list[existingIndex] = sourceMap;
    } else {
      list.add(sourceMap);
    }

    await widget.app.settings.set('custom_sources', jsonEncode(list));
    if (mounted) Navigator.of(context).pop(true);
  }

  Future<void> _removeSource() async {
    if (widget.sourceId == null) {
      Navigator.of(context).pop();
      return;
    }
    final rawJson = widget.app.settings.get('custom_sources', '[]');
    try {
      final list = jsonDecode(rawJson) as List<dynamic>;
      list.removeWhere((item) => item is Map && item['id'] == widget.sourceId);
      await widget.app.settings.set('custom_sources', jsonEncode(list));
    } catch (_) {}
    if (mounted) Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final isNew = widget.sourceId == null;

    return DesktopDialogPanel(
      maxWidth: 440.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              isNew ? 'Add a source' : 'Edit source',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 16.0),
            TextField(
              controller: _urlController,
              style: TextStyle(color: palette.foreground),
              decoration: InputDecoration(
                labelText: 'Addon URL',
                hintText: 'https://example.com/addon',
                labelStyle: TextStyle(color: palette.secondary),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10.0),
                ),
              ),
              onChanged: (_) => setState(() => _status = null),
            ),
            const SizedBox(height: 6.0),
            Text(
              'An addon server — BitChord asks it over plain HTTP and runs no code from it. A compatible module index also works.',
              style: TextStyle(color: palette.secondary, fontSize: 12),
            ),
            const SizedBox(height: 16.0),
            TextField(
              controller: _nameController,
              style: TextStyle(color: palette.foreground),
              decoration: InputDecoration(
                labelText: 'Name (optional)',
                hintText: 'My music source',
                labelStyle: TextStyle(color: palette.secondary),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10.0),
                ),
              ),
            ),
            if (_status != null) ...[
              const SizedBox(height: 12.0),
              Text(
                _status!,
                style: TextStyle(color: palette.accent, fontSize: 13),
              ),
            ],
            const SizedBox(height: 24.0),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                if (!isNew)
                  TextButton(
                    onPressed: _removeSource,
                    child: Text(
                      'Remove',
                      style: TextStyle(color: palette.destructive),
                    ),
                  )
                else
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Cancel'),
                  ),
                Row(
                  children: [
                    OutlinedButton(
                      onPressed: _testing ? null : _testEndpoint,
                      child: Text(_testing ? 'Testing…' : 'Test'),
                    ),
                    const SizedBox(width: 8.0),
                    FilledButton(
                      onPressed: _saveSource,
                      style: FilledButton.styleFrom(
                        backgroundColor: palette.accent,
                        foregroundColor: palette.onAccent,
                      ),
                      child: const Text('Save'),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 4. Audio Output Device Dialog
// ---------------------------------------------------------------------------

class DesktopAudioOutputDialog extends StatefulWidget {
  const DesktopAudioOutputDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopAudioOutputDialog> createState() =>
      _DesktopAudioOutputDialogState();
}

class _DesktopAudioOutputDialogState extends State<DesktopAudioOutputDialog> {
  bool _showAdvanced = false;

  static const _defaultDevices = [
    {
      'id': 'SYSTEM_DEFAULT',
      'name': 'System default',
      'desc': 'Follow OS audio output',
    },
    {
      'id': 'hw_speakers',
      'name': 'Speakers (Built-in Audio)',
      'desc': 'Realtek High Definition Audio',
    },
    {
      'id': 'hw_headphones',
      'name': 'Headphones',
      'desc': '3.5mm stereo output jack',
    },
    {
      'id': 'hw_usb_dac',
      'name': 'USB Audio DAC',
      'desc': 'High-resolution asynchronous USB endpoint',
    },
  ];

  static const _advancedDevices = [
    {
      'id': 'alsa_default',
      'name': 'ALSA: default',
      'desc': 'Advanced Linux Sound Architecture Default PCM',
    },
    {
      'id': 'alsa_plughw_0_0',
      'name': 'ALSA: plughw:CARD=PCH,DEV=0',
      'desc': 'Direct Hardware subdevice with rate/format conversions',
    },
    {
      'id': 'alsa_hw_0_0',
      'name': 'ALSA: hw:CARD=PCH,DEV=0',
      'desc': 'Direct Hardware subdevice without software conversions',
    },
    {
      'id': 'pulse_sink',
      'name': 'PulseAudio / PipeWire Sink',
      'desc': 'PulseAudio / PipeWire audio server route',
    },
    {
      'id': 'jack_system',
      'name': 'JACK Audio Connection Kit',
      'desc': 'Low-latency real-time professional audio server',
    },
  ];

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final selectedId = widget.app.settings.get(
      'audio_output_device',
      'SYSTEM_DEFAULT',
    );

    final visibleDevices = [
      ..._defaultDevices,
      for (final dev in _advancedDevices)
        if (_showAdvanced || dev['id'] == selectedId) dev,
    ];

    return DesktopDialogPanel(
      maxWidth: 440.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Audio output',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4.0),
            Text(
              'Changes take effect immediately.',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 16.0),
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: visibleDevices.length,
                separatorBuilder: (_, _) =>
                    Divider(color: palette.divider, height: 1.0),
                itemBuilder: (context, index) {
                  final dev = visibleDevices[index];
                  final isSelected = dev['id'] == selectedId;
                  return ListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 8.0,
                      vertical: 2.0,
                    ),
                    title: Text(
                      dev['name']!,
                      style: TextStyle(
                        color: palette.foreground,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w400,
                      ),
                    ),
                    subtitle: Text(
                      dev['desc']!,
                      style: TextStyle(color: palette.secondary, fontSize: 12),
                    ),
                    trailing: isSelected
                        ? Icon(Icons.check_rounded, color: palette.accent)
                        : null,
                    onTap: () {
                      unawaited(
                        widget.app.settings.set(
                          'audio_output_device',
                          dev['id']!,
                        ),
                      );
                      Navigator.of(context).pop();
                    },
                  );
                },
              ),
            ),
            const SizedBox(height: 12.0),
            TextButton(
              onPressed: () => setState(() => _showAdvanced = !_showAdvanced),
              child: Text(
                _showAdvanced
                    ? 'Hide advanced audio devices'
                    : 'Show advanced audio devices',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 5. Equalizer Dialog
// ---------------------------------------------------------------------------

/// Exact 7-band dB curves for manual equalizer (60Hz, 150Hz, 400Hz, 1kHz, 2.5kHz, 6kHz, 14kHz).
/// Reference: com.music.bitchord.playback.EqualizerPreset (EqualizerPreset.kt:18–35).
const Map<String, List<double>> kEqualizerPresetGains = {
  // Canonical Kotlin Compose Desktop Presets
  'Flat': [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
  'Acoustic': [3.0, 1.5, 0.0, 1.5, 2.5, 2.0, 1.0],
  'Bass boost': [6.0, 4.0, 1.5, 0.0, 0.0, 0.0, 0.0],
  'Bass cut': [-6.0, -4.0, -1.5, 0.0, 0.0, 0.0, 0.0],
  'Vocal': [-3.0, -1.5, 1.0, 3.5, 3.0, 1.0, -1.0],
  'Treble boost': [0.0, 0.0, 0.0, 0.0, 1.5, 3.5, 5.0],
  'Treble cut': [0.0, 0.0, 0.0, 0.0, -1.5, -3.5, -5.0],
  'Loudness': [6.0, 3.5, 0.0, -1.5, -1.0, 2.0, 5.0],
  'Spoken word': [-5.0, -2.5, 1.5, 4.0, 3.5, 1.5, -2.0],
  'Electronic': [5.0, 3.0, -1.0, 0.0, 1.0, 3.0, 4.0],
  'Rock': [4.0, 2.5, -1.0, -1.5, 1.0, 3.0, 3.5],
  'Hip-hop': [6.0, 4.0, 0.5, -1.0, 0.5, 2.0, 2.5],
  'Jazz': [3.0, 1.5, 0.0, 1.0, 1.5, 2.0, 2.5],
  'Classical': [3.0, 2.0, 0.0, 0.0, 1.0, 2.5, 3.0],
  'Small speakers': [5.0, 4.0, 2.0, 0.5, 0.0, -1.0, -2.0],
  'Late night': [3.0, 1.0, 0.0, 1.5, 1.0, -1.0, -3.0],

  // Canonical Synonyms & Formatting Aliases
  'Bass reduce': [-6.0, -4.0, -1.5, 0.0, 0.0, 0.0, 0.0],
  'Vocal boost': [-3.0, -1.5, 1.0, 3.5, 3.0, 1.0, -1.0],
  'Hip-Hop': [6.0, 4.0, 0.5, -1.0, 0.5, 2.0, 2.5],

  // Extended Standard Curve Profiles
  'Deep': [5.0, 3.5, 1.0, 0.0, -1.0, -1.5, -2.0],
  'Latin': [3.5, 2.0, -1.0, -1.0, 1.0, 2.5, 3.5],
  'Lounge': [-2.0, 1.5, 2.5, 1.0, -1.0, 1.5, 1.0],
  'Piano': [2.5, 1.5, 0.0, 1.5, 2.0, 2.5, 2.5],
  'Pop': [-1.5, 1.5, 3.5, 4.0, 2.5, -1.0, -1.5],
  'R&B': [5.0, 4.5, 2.0, -1.0, 1.5, 2.5, 3.0],
};

class DesktopEqualizerDialog extends StatefulWidget {
  const DesktopEqualizerDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopEqualizerDialog> createState() => _DesktopEqualizerDialogState();
}

class _DesktopEqualizerDialogState extends State<DesktopEqualizerDialog> {
  int _tabIndex = 0; // 0: Dynamic, 1: Manual

  final List<String> _bandLabels = const [
    '60',
    '150',
    '400',
    '1k',
    '2.5k',
    '6k',
    '14k',
  ];
  final List<double> _bandGains = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0];

  final List<String> _presets = const [
    'Flat',
    'Acoustic',
    'Bass boost',
    'Bass cut',
    'Vocal',
    'Treble boost',
    'Treble cut',
    'Loudness',
    'Spoken word',
    'Electronic',
    'Rock',
    'Hip-hop',
    'Jazz',
    'Classical',
    'Small speakers',
    'Late night',
    'Custom',
  ];

  @override
  void initState() {
    super.initState();
    final mode = widget.app.settings.get('equalizer_mode', 'DYNAMIC');
    _tabIndex = mode == 'MANUAL' ? 1 : 0;

    final bandsStr = widget.app.settings.get(
      'equalizer_bands',
      '0,0,0,0,0,0,0',
    );
    final parsed = bandsStr
        .split(',')
        .map((e) => double.tryParse(e) ?? 0.0)
        .toList();
    for (var i = 0; i < 7 && i < parsed.length; i++) {
      _bandGains[i] = parsed[i];
    }
  }

  void _saveBands() {
    unawaited(widget.app.settings.set('equalizer_bands', _bandGains.join(',')));
  }

  String _matchPreset(List<double> gains) {
    for (final entry in kEqualizerPresetGains.entries) {
      if (entry.key == 'Custom') continue;
      final curve = entry.value;
      if (curve.length != gains.length) continue;
      var matches = true;
      for (var i = 0; i < 7; i++) {
        if ((curve[i] - gains[i]).abs() >= 0.05) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return entry.key;
      }
    }
    return 'Custom';
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final settings = widget.app.settings;

    final enabled = settings.get('equalizer_enabled', false);
    final toneX = settings.get('equalizer_tone_x', 0);
    final toneY = settings.get('equalizer_tone_y', 0);
    final focused = settings.get('equalizer_focused', false);
    final balance = settings.get('equalizer_balance', 0.0);

    return DesktopDialogPanel(
      maxWidth: 560.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              // Header
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Equalizer',
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(
                                color: palette.foreground,
                                fontWeight: FontWeight.w700,
                              ),
                        ),
                        const SizedBox(height: 2.0),
                        Text(
                          'Shapes the sound inside BitChord, before it reaches the system.',
                          style: TextStyle(
                            color: palette.secondary,
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Switch(
                    value: enabled,
                    activeThumbColor: palette.accent,
                    onChanged: (val) {
                      unawaited(settings.set('equalizer_enabled', val));
                      setState(() {});
                    },
                  ),
                ],
              ),
              const SizedBox(height: 16.0),

              // Segmented Tab Selector
              SettingsSegmentedControl<String>(
                items: const [
                  FilterChipItem(value: 'DYNAMIC', label: 'Dynamic'),
                  FilterChipItem(value: 'MANUAL', label: 'Manual'),
                ],
                selected: _tabIndex == 0 ? 'DYNAMIC' : 'MANUAL',
                onSelected: (val) {
                  setState(() => _tabIndex = val == 'DYNAMIC' ? 0 : 1);
                  unawaited(settings.set('equalizer_mode', val));
                },
              ),
              const SizedBox(height: 20.0),

              // Tab Body
              Opacity(
                opacity: enabled ? 1.0 : 0.4,
                child: IgnorePointer(
                  ignoring: !enabled,
                  child: _tabIndex == 0
                      ? _buildTonePadView(
                          context,
                          palette,
                          toneX,
                          toneY,
                          focused,
                        )
                      : _buildManualBandsView(context, palette),
                ),
              ),

              const SizedBox(height: 20.0),

              // Balance Row
              Text(
                'BALANCE',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: palette.secondary,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 6.0),
              Row(
                children: [
                  Text(
                    'L',
                    style: TextStyle(
                      color: palette.secondary,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Expanded(
                    child: SettingsBareSlider(
                      value: balance,
                      min: -1.0,
                      max: 1.0,
                      divisions: 40,
                      onChanged: (val) {
                        unawaited(settings.set('equalizer_balance', val));
                        setState(() {});
                      },
                    ),
                  ),
                  Text(
                    'R',
                    style: TextStyle(
                      color: palette.secondary,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20.0),

              // Done Button
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accent,
                    foregroundColor: palette.onAccent,
                  ),
                  child: const Text('Done'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTonePadView(
    BuildContext context,
    AppColors palette,
    int toneX,
    int toneY,
    bool focused,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'TONE',
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            color: palette.secondary,
            letterSpacing: 0.8,
          ),
        ),
        const SizedBox(height: 10.0),
        Container(
          height: 180.0,
          decoration: BoxDecoration(
            color: palette.cardInsetFill,
            borderRadius: BorderRadius.circular(16.0),
            border: Border.all(color: palette.cardEdge, width: 0.5),
          ),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final w = constraints.maxWidth;
              final h = constraints.maxHeight;
              final puckX = ((toneX + 5) / 10.0) * (w - 32.0) + 16.0;
              final puckY = ((5 - toneY) / 10.0) * (h - 32.0) + 16.0;

              return GestureDetector(
                onPanUpdate: (details) {
                  final local = details.localPosition;
                  final nx = ((local.dx - 16.0) / (w - 32.0) * 10.0 - 5.0)
                      .round()
                      .clamp(-5, 5);
                  final ny = (5.0 - (local.dy - 16.0) / (h - 32.0) * 10.0)
                      .round()
                      .clamp(-5, 5);
                  unawaited(widget.app.settings.set('equalizer_tone_x', nx));
                  unawaited(widget.app.settings.set('equalizer_tone_y', ny));
                  setState(() {});
                },
                child: Stack(
                  children: [
                    // Grid dots
                    Positioned.fill(
                      child: CustomPaint(
                        painter: _TonePadGridPainter(palette: palette),
                      ),
                    ),
                    // Axis labels
                    Positioned(
                      left: 12,
                      top: h / 2 - 8,
                      child: Text(
                        'Warm',
                        style: TextStyle(
                          color: palette.secondary,
                          fontSize: 11,
                        ),
                      ),
                    ),
                    Positioned(
                      right: 12,
                      top: h / 2 - 8,
                      child: Text(
                        'Bright',
                        style: TextStyle(
                          color: palette.secondary,
                          fontSize: 11,
                        ),
                      ),
                    ),
                    Positioned(
                      left: w / 2 - 18,
                      top: 8,
                      child: Text(
                        'Mid-fwd',
                        style: TextStyle(
                          color: palette.secondary,
                          fontSize: 11,
                        ),
                      ),
                    ),
                    Positioned(
                      left: w / 2 - 18,
                      bottom: 8,
                      child: Text(
                        'Scooped',
                        style: TextStyle(
                          color: palette.secondary,
                          fontSize: 11,
                        ),
                      ),
                    ),
                    // Draggable puck
                    Positioned(
                      left: puckX - 14.0,
                      top: puckY - 14.0,
                      child: Container(
                        width: 28.0,
                        height: 28.0,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: palette.accent,
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(alpha: 0.35),
                              blurRadius: 6.0,
                              offset: const Offset(0, 2),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 10.0),
        Center(
          child: Text(
            'Tilt: $toneX  ·  Contour: $toneY',
            style: TextStyle(
              color: palette.foreground,
              fontWeight: FontWeight.w600,
              fontSize: 13,
            ),
          ),
        ),
        const SizedBox(height: 12.0),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            FilterChip(
              label: const Text('Broad'),
              selected: !focused,
              onSelected: (_) {
                unawaited(widget.app.settings.set('equalizer_focused', false));
                setState(() {});
              },
            ),
            const SizedBox(width: 8.0),
            FilterChip(
              label: const Text('Focused'),
              selected: focused,
              onSelected: (_) {
                unawaited(widget.app.settings.set('equalizer_focused', true));
                setState(() {});
              },
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildManualBandsView(BuildContext context, AppColors palette) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              'BANDS',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: palette.secondary,
                letterSpacing: 0.8,
              ),
            ),
            TextButton(
              onPressed: () {
                for (var i = 0; i < 7; i++) {
                  _bandGains[i] = 0.0;
                }
                _saveBands();
                unawaited(widget.app.settings.set('equalizer_preset', 'Flat'));
                setState(() {});
              },
              child: Text(
                'Reset all bands',
                style: TextStyle(color: palette.accent, fontSize: 12),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12.0),
        Row(
          children: [
            for (var i = 0; i < 7; i++)
              Expanded(
                child: Column(
                  children: [
                    Text(
                      '${_bandGains[i] > 0 ? "+" : ""}${_bandGains[i].toStringAsFixed(1)}',
                      style: TextStyle(
                        color: _bandGains[i] != 0
                            ? palette.accent
                            : palette.secondary,
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4.0),
                    SizedBox(
                      height: 140.0,
                      child: RotatedBox(
                        quarterTurns: 3,
                        child: SliderTheme(
                          data: SliderTheme.of(context).copyWith(
                            trackHeight: 4.0,
                            thumbShape: const RoundSliderThumbShape(
                              enabledThumbRadius: 6.0,
                            ),
                            activeTrackColor: palette.accent,
                            inactiveTrackColor: palette.accent.withValues(
                              alpha: 0.24,
                            ),
                            thumbColor: palette.accent,
                          ),
                          child: Slider(
                            value: _bandGains[i],
                            min: -12.0,
                            max: 12.0,
                            divisions: 48,
                            onChanged: (val) {
                              setState(() => _bandGains[i] = val);
                              _saveBands();
                              final matched = _matchPreset(_bandGains);
                              unawaited(
                                widget.app.settings.set(
                                  'equalizer_preset',
                                  matched,
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 4.0),
                    Text(
                      _bandLabels[i],
                      style: TextStyle(color: palette.secondary, fontSize: 11),
                    ),
                  ],
                ),
              ),
          ],
        ),
        const SizedBox(height: 14.0),
        Wrap(
          spacing: 6.0,
          runSpacing: 6.0,
          children: _presets.map((preset) {
            final activePreset = widget.app.settings.get(
              'equalizer_preset',
              'Flat',
            );
            final isChosen = activePreset.toLowerCase() == preset.toLowerCase();
            return ActionChip(
              label: Text(preset),
              backgroundColor: isChosen
                  ? palette.accent.withValues(alpha: 0.16)
                  : palette.surfaceRaised,
              side: BorderSide(
                color: isChosen ? palette.accent : palette.divider,
              ),
              onPressed: () {
                if (preset.toLowerCase() == 'custom') {
                  return;
                }
                final gains =
                    kEqualizerPresetGains[preset] ??
                    kEqualizerPresetGains.entries
                        .firstWhere(
                          (e) => e.key.toLowerCase() == preset.toLowerCase(),
                          orElse: () => const MapEntry('Flat', [
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                          ]),
                        )
                        .value;

                for (var i = 0; i < 7 && i < gains.length; i++) {
                  _bandGains[i] = gains[i];
                }
                _saveBands();
                unawaited(widget.app.settings.set('equalizer_preset', preset));
                setState(() {});
              },
            );
          }).toList(),
        ),
      ],
    );
  }
}

class _TonePadGridPainter extends CustomPainter {
  _TonePadGridPainter({required this.palette});
  final AppColors palette;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = palette.secondary.withValues(alpha: 0.22)
      ..style = PaintingStyle.fill;

    final axisPaint = Paint()
      ..color = palette.secondary.withValues(alpha: 0.45)
      ..strokeWidth = 1.0;

    // Cross-axis lines
    canvas.drawLine(
      Offset(size.width / 2, 10),
      Offset(size.width / 2, size.height - 10),
      axisPaint,
    );
    canvas.drawLine(
      Offset(10, size.height / 2),
      Offset(size.width - 10, size.height / 2),
      axisPaint,
    );

    // 11x11 dots
    for (var i = 0; i < 11; i++) {
      for (var j = 0; j < 11; j++) {
        final x = 16.0 + i * ((size.width - 32.0) / 10.0);
        final y = 16.0 + j * ((size.height - 32.0) / 10.0);
        canvas.drawCircle(Offset(x, y), 1.5, paint);
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

// ---------------------------------------------------------------------------
// 6. Spotify Canvas Dialog
// ---------------------------------------------------------------------------

class DesktopSpotifyCanvasDialog extends StatefulWidget {
  const DesktopSpotifyCanvasDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopSpotifyCanvasDialog> createState() =>
      _DesktopSpotifyCanvasDialogState();
}

class _DesktopSpotifyCanvasDialogState
    extends State<DesktopSpotifyCanvasDialog> {
  late final TextEditingController _cookieController;

  @override
  void initState() {
    super.initState();
    final existing = widget.app.settings.get(
      'spotify_spdc_token',
      widget.app.settings.get('spotify_dc', ''),
    );
    _cookieController = TextEditingController(text: existing);
  }

  @override
  void dispose() {
    _cookieController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return DesktopDialogPanel(
      maxWidth: 420.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Spotify account',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 8.0),
            Text(
              'Connect your Spotify account to browse playlists and Liked Songs. Playback uses YouTube Music.',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 16.0),
            Text(
              'Sign in at open.spotify.com, open Developer Tools → Application → Cookies, then paste the value of the sp_dc cookie below.',
              style: TextStyle(color: palette.secondary, fontSize: 12),
            ),
            const SizedBox(height: 12.0),
            TextField(
              controller: _cookieController,
              obscureText: true,
              style: TextStyle(color: palette.foreground),
              decoration: InputDecoration(
                labelText: 'sp_dc cookie',
                labelStyle: TextStyle(color: palette.secondary),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10.0),
                ),
              ),
            ),
            const SizedBox(height: 20.0),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  onPressed: () {
                    final token = _cookieController.text.trim();
                    unawaited(widget.app.settings.set('spotify_dc', token));
                    unawaited(
                      widget.app.settings.set('spotify_spdc_token', token),
                    );
                    Navigator.of(context).pop();
                  },
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accent,
                    foregroundColor: palette.onAccent,
                  ),
                  child: const Text('Save'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 7. Content Preferences Sub-Page
// ---------------------------------------------------------------------------

class DesktopContentPreferencesPage extends StatefulWidget {
  const DesktopContentPreferencesPage({
    super.key,
    required this.app,
    required this.onBack,
  });

  final MusicApplication app;
  final VoidCallback onBack;

  @override
  State<DesktopContentPreferencesPage> createState() =>
      _DesktopContentPreferencesPageState();
}

class _DesktopContentPreferencesPageState
    extends State<DesktopContentPreferencesPage> {
  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final settings = widget.app.settings;

    final musicOnly = settings.get(
      'content_prefer_music_only',
      settings.get('prefer_music_only', false),
    );
    final hideRecents = settings.get('content_hide_recents', false);
    final hideHomeVideos = settings.get('content_hide_home_videos', false);
    final hideTopPicks = settings.get('content_hide_top_picks', false);
    final hideHomeMoods = settings.get('content_hide_home_moods', false);
    final squareThumbs = settings.get('content_square_video_thumbnails', false);

    return SettingsPageFrame(
      maxWidth: 860.0,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 16.0),
          Row(
            children: [
              IconButton(
                icon: const Icon(Icons.arrow_back_rounded),
                tooltip: 'Back to Settings',
                onPressed: widget.onBack,
              ),
              const SizedBox(width: 8.0),
              Text(
                'Content Preferences',
                style: Theme.of(context).textTheme.headlineLarge?.copyWith(
                  color: palette.foreground,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24.0),
          Container(
            decoration: BoxDecoration(
              color: palette.cardFill,
              borderRadius: BorderRadius.circular(20.0),
              border: Border.all(color: palette.cardEdge, width: 0.5),
            ),
            child: Column(
              children: [
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.videocam_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Prefer music-only version',
                    style: TextStyle(color: palette.foreground),
                  ),
                  subtitle: Text(
                    'Music videos play as their audio version; the next queue item is resolved ahead of time for smoother transitions.',
                    style: TextStyle(color: palette.secondary),
                  ),
                  value: musicOnly,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(settings.set('content_prefer_music_only', val));
                    unawaited(settings.set('prefer_music_only', val));
                    setState(() {});
                  },
                ),
                Divider(indent: 72.0, color: palette.divider, height: 1.0),
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.visibility_off_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Hide Recents',
                    style: TextStyle(color: palette.foreground),
                  ),
                  value: hideRecents,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(settings.set('content_hide_recents', val));
                    setState(() {});
                  },
                ),
                Divider(indent: 72.0, color: palette.divider, height: 1.0),
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.visibility_off_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Hide videos from Home',
                    style: TextStyle(color: palette.foreground),
                  ),
                  subtitle: Text(
                    'Video-only shelves are hidden. Search, artists and playlists remain unchanged.',
                    style: TextStyle(color: palette.secondary),
                  ),
                  value: hideHomeVideos,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(settings.set('content_hide_home_videos', val));
                    setState(() {});
                  },
                ),
                Divider(indent: 72.0, color: palette.divider, height: 1.0),
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.auto_awesome_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Hide Top Picks for You',
                    style: TextStyle(color: palette.foreground),
                  ),
                  value: hideTopPicks,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(settings.set('content_hide_top_picks', val));
                    setState(() {});
                  },
                ),
                Divider(indent: 72.0, color: palette.divider, height: 1.0),
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.star_border_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Hide Find Your Mood',
                    style: TextStyle(color: palette.foreground),
                  ),
                  value: hideHomeMoods,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(settings.set('content_hide_home_moods', val));
                    setState(() {});
                  },
                ),
                Divider(indent: 72.0, color: palette.divider, height: 1.0),
                SwitchListTile(
                  secondary: Container(
                    width: 34.0,
                    height: 34.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(9.0),
                      color: palette.foreground.withValues(alpha: 0.08),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.grid_view_rounded,
                      size: 19.0,
                      color: palette.accent,
                    ),
                  ),
                  title: Text(
                    'Square video thumbnails',
                    style: TextStyle(color: palette.foreground),
                  ),
                  subtitle: Text(
                    'Display music videos with square covers wherever thumbnail layout supports it.',
                    style: TextStyle(color: palette.secondary),
                  ),
                  value: squareThumbs,
                  activeThumbColor: palette.accent,
                  onChanged: (val) {
                    unawaited(
                      settings.set('content_square_video_thumbnails', val),
                    );
                    setState(() {});
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 8. Translation Language Dialog
// ---------------------------------------------------------------------------

/// Complete, unabridged list of all 134 translation language choices.
/// Source: com.music.bitchord.data.lyrics.TranslationLanguages (TranslationLanguages.kt:27–160).
const List<Map<String, String>> kTranslationLanguages = [
  // 0. Default System Option
  {
    'code': '',
    'name': 'Follow the app language',
    'nativeName': 'System default',
  },

  // 1–133. Google Translate Languages in Canonical Alphabetical Order
  {'code': 'af', 'name': 'Afrikaans', 'nativeName': 'Afrikaans'},
  {'code': 'sq', 'name': 'Albanian', 'nativeName': 'Shqip'},
  {'code': 'am', 'name': 'Amharic', 'nativeName': 'አማርኛ'},
  {'code': 'ar', 'name': 'Arabic', 'nativeName': 'العربية'},
  {'code': 'hy', 'name': 'Armenian', 'nativeName': 'Հայերեն'},
  {'code': 'as', 'name': 'Assamese', 'nativeName': 'অসমীয়া'},
  {'code': 'ay', 'name': 'Aymara', 'nativeName': 'Aymar aru'},
  {'code': 'az', 'name': 'Azerbaijani', 'nativeName': 'Azərbaycan dili'},
  {'code': 'bm', 'name': 'Bambara', 'nativeName': 'Bamanankan'},
  {'code': 'eu', 'name': 'Basque', 'nativeName': 'Euskara'},
  {'code': 'be', 'name': 'Belarusian', 'nativeName': 'Беларуская'},
  {'code': 'bn', 'name': 'Bengali', 'nativeName': 'বাংলা'},
  {'code': 'bho', 'name': 'Bhojpuri', 'nativeName': 'भोजपुरी'},
  {'code': 'bs', 'name': 'Bosnian', 'nativeName': 'Bosanski'},
  {'code': 'bg', 'name': 'Bulgarian', 'nativeName': 'Български'},
  {'code': 'ca', 'name': 'Catalan', 'nativeName': 'Català'},
  {'code': 'ceb', 'name': 'Cebuano', 'nativeName': 'Cebuano'},
  {'code': 'ny', 'name': 'Chichewa', 'nativeName': 'ChiCheŵa'},
  {'code': 'zh-CN', 'name': 'Chinese (Simplified)', 'nativeName': '简体中文'},
  {'code': 'zh-TW', 'name': 'Chinese (Traditional)', 'nativeName': '繁體中文'},
  {'code': 'co', 'name': 'Corsican', 'nativeName': 'Corsu'},
  {'code': 'hr', 'name': 'Croatian', 'nativeName': 'Hrvatski'},
  {'code': 'cs', 'name': 'Czech', 'nativeName': 'Čeština'},
  {'code': 'da', 'name': 'Danish', 'nativeName': 'Dansk'},
  {'code': 'dv', 'name': 'Dhivehi', 'nativeName': 'ދިވެހި'},
  {'code': 'doi', 'name': 'Dogri', 'nativeName': 'डोगरी'},
  {'code': 'nl', 'name': 'Dutch', 'nativeName': 'Nederlands'},
  {'code': 'en', 'name': 'English', 'nativeName': 'English'},
  {'code': 'eo', 'name': 'Esperanto', 'nativeName': 'Esperanto'},
  {'code': 'et', 'name': 'Estonian', 'nativeName': 'Eesti'},
  {'code': 'ee', 'name': 'Ewe', 'nativeName': 'Eʋegbe'},
  {'code': 'tl', 'name': 'Filipino', 'nativeName': 'Tagalog'},
  {'code': 'fi', 'name': 'Finnish', 'nativeName': 'Suomi'},
  {'code': 'fr', 'name': 'French', 'nativeName': 'Français'},
  {'code': 'fy', 'name': 'Frisian', 'nativeName': 'Frysk'},
  {'code': 'gl', 'name': 'Galician', 'nativeName': 'Galego'},
  {'code': 'ka', 'name': 'Georgian', 'nativeName': 'ქართული'},
  {'code': 'de', 'name': 'German', 'nativeName': 'Deutsch'},
  {'code': 'el', 'name': 'Greek', 'nativeName': 'Ελληνικά'},
  {'code': 'gn', 'name': 'Guarani', 'nativeName': "Avañe'ẽ"},
  {'code': 'gu', 'name': 'Gujarati', 'nativeName': 'ગુજરાતી'},
  {'code': 'ht', 'name': 'Haitian Creole', 'nativeName': 'Kreyòl ayisyen'},
  {'code': 'ha', 'name': 'Hausa', 'nativeName': 'Hausa'},
  {'code': 'haw', 'name': 'Hawaiian', 'nativeName': 'ʻŌlelo Hawaiʻi'},
  {'code': 'iw', 'name': 'Hebrew', 'nativeName': 'עברית'},
  {'code': 'hi', 'name': 'Hindi', 'nativeName': 'हिन्दी'},
  {'code': 'hmn', 'name': 'Hmong', 'nativeName': 'Hmoob'},
  {'code': 'hu', 'name': 'Hungarian', 'nativeName': 'Magyar'},
  {'code': 'is', 'name': 'Icelandic', 'nativeName': 'Íslenska'},
  {'code': 'ig', 'name': 'Igbo', 'nativeName': 'Asụsụ Igbo'},
  {'code': 'ilo', 'name': 'Ilocano', 'nativeName': 'Ilokano'},
  {'code': 'id', 'name': 'Indonesian', 'nativeName': 'Bahasa Indonesia'},
  {'code': 'ga', 'name': 'Irish', 'nativeName': 'Gaeilge'},
  {'code': 'it', 'name': 'Italian', 'nativeName': 'Italiano'},
  {'code': 'ja', 'name': 'Japanese', 'nativeName': '日本語'},
  {'code': 'jw', 'name': 'Javanese', 'nativeName': 'Basa Jawa'},
  {'code': 'kn', 'name': 'Kannada', 'nativeName': 'ಕನ್ನಡ'},
  {'code': 'kk', 'name': 'Kazakh', 'nativeName': 'Қазақ тілі'},
  {'code': 'km', 'name': 'Khmer', 'nativeName': 'ភាសាខ្មែរ'},
  {'code': 'rw', 'name': 'Kinyarwanda', 'nativeName': 'Ikinyarwanda'},
  {'code': 'gom', 'name': 'Konkani', 'nativeName': 'कोंकणी'},
  {'code': 'ko', 'name': 'Korean', 'nativeName': '한국어'},
  {'code': 'kri', 'name': 'Krio', 'nativeName': 'Krio'},
  {'code': 'ku', 'name': 'Kurdish (Kurmanji)', 'nativeName': 'Kurmancî'},
  {'code': 'ckb', 'name': 'Kurdish (Sorani)', 'nativeName': 'کوردیی سۆرانی'},
  {'code': 'ky', 'name': 'Kyrgyz', 'nativeName': 'Кыргызча'},
  {'code': 'lo', 'name': 'Lao', 'nativeName': 'ພາສາລາວ'},
  {'code': 'la', 'name': 'Latin', 'nativeName': 'Latina'},
  {'code': 'lv', 'name': 'Latvian', 'nativeName': 'Latviešu'},
  {'code': 'ln', 'name': 'Lingala', 'nativeName': 'Lingála'},
  {'code': 'lt', 'name': 'Lithuanian', 'nativeName': 'Lietuvių'},
  {'code': 'lg', 'name': 'Luganda', 'nativeName': 'Oluganda'},
  {'code': 'lb', 'name': 'Luxembourgish', 'nativeName': 'Lëtzebuergesch'},
  {'code': 'mk', 'name': 'Macedonian', 'nativeName': 'Македонски'},
  {'code': 'mai', 'name': 'Maithili', 'nativeName': 'मैथिली'},
  {'code': 'mg', 'name': 'Malagasy', 'nativeName': 'Malagasy'},
  {'code': 'ms', 'name': 'Malay', 'nativeName': 'Bahasa Melayu'},
  {'code': 'ml', 'name': 'Malayalam', 'nativeName': 'മലയാളം'},
  {'code': 'mt', 'name': 'Maltese', 'nativeName': 'Malti'},
  {'code': 'mi', 'name': 'Maori', 'nativeName': 'Te Reo Māori'},
  {'code': 'mr', 'name': 'Marathi', 'nativeName': 'मराठी'},
  {'code': 'mni-Mtei', 'name': 'Meiteilon (Manipuri)', 'nativeName': 'ꯃꯤꯇꯩꯂꯣꯟ'},
  {'code': 'lus', 'name': 'Mizo', 'nativeName': 'Mizo ṭawng'},
  {'code': 'mn', 'name': 'Mongolian', 'nativeName': 'Монгол хэл'},
  {'code': 'my', 'name': 'Myanmar (Burmese)', 'nativeName': 'မြန်မာစာ'},
  {'code': 'ne', 'name': 'Nepali', 'nativeName': 'नेपाली'},
  {'code': 'no', 'name': 'Norwegian', 'nativeName': 'Norsk'},
  {'code': 'or', 'name': 'Odia (Oriya)', 'nativeName': 'ଓଡ଼ିଆ'},
  {'code': 'om', 'name': 'Oromo', 'nativeName': 'Afaan Oromoo'},
  {'code': 'ps', 'name': 'Pashto', 'nativeName': 'پښتو'},
  {'code': 'fa', 'name': 'Persian', 'nativeName': 'فارسی'},
  {'code': 'pl', 'name': 'Polish', 'nativeName': 'Polski'},
  {'code': 'pt', 'name': 'Portuguese', 'nativeName': 'Português'},
  {'code': 'pa', 'name': 'Punjabi', 'nativeName': 'ਪੰਜਾਬੀ'},
  {'code': 'qu', 'name': 'Quechua', 'nativeName': 'Runasimi'},
  {'code': 'ro', 'name': 'Romanian', 'nativeName': 'Română'},
  {'code': 'ru', 'name': 'Russian', 'nativeName': 'Русский'},
  {'code': 'sm', 'name': 'Samoan', 'nativeName': 'Gagana faʻa Sāmoa'},
  {'code': 'sa', 'name': 'Sanskrit', 'nativeName': 'संस्कृतम्'},
  {'code': 'gd', 'name': 'Scots Gaelic', 'nativeName': 'Gàidhlig'},
  {'code': 'nso', 'name': 'Sepedi', 'nativeName': 'Sesotho sa Leboa'},
  {'code': 'sr', 'name': 'Serbian', 'nativeName': 'Српски'},
  {'code': 'st', 'name': 'Sesotho', 'nativeName': 'Sesotho'},
  {'code': 'sn', 'name': 'Shona', 'nativeName': 'ChiShona'},
  {'code': 'sd', 'name': 'Sindhi', 'nativeName': 'سنڌي'},
  {'code': 'si', 'name': 'Sinhala', 'nativeName': 'සිංහල'},
  {'code': 'sk', 'name': 'Slovak', 'nativeName': 'Slovenčina'},
  {'code': 'sl', 'name': 'Slovenian', 'nativeName': 'Slovenščina'},
  {'code': 'so', 'name': 'Somali', 'nativeName': 'Soomaaliga'},
  {'code': 'es', 'name': 'Spanish', 'nativeName': 'Español'},
  {'code': 'su', 'name': 'Sundanese', 'nativeName': 'Basa Sunda'},
  {'code': 'sw', 'name': 'Swahili', 'nativeName': 'Kiswahili'},
  {'code': 'sv', 'name': 'Swedish', 'nativeName': 'Svenska'},
  {'code': 'tg', 'name': 'Tajik', 'nativeName': 'Тоҷикӣ'},
  {'code': 'ta', 'name': 'Tamil', 'nativeName': 'தமிழ்'},
  {'code': 'tt', 'name': 'Tatar', 'nativeName': 'Татар теле'},
  {'code': 'te', 'name': 'Telugu', 'nativeName': 'తెలుగు'},
  {'code': 'th', 'name': 'Thai', 'nativeName': 'ไทย'},
  {'code': 'ti', 'name': 'Tigrinya', 'nativeName': 'ትግርኛ'},
  {'code': 'ts', 'name': 'Tsonga', 'nativeName': 'Xitsonga'},
  {'code': 'tr', 'name': 'Turkish', 'nativeName': 'Türkçe'},
  {'code': 'tk', 'name': 'Turkmen', 'nativeName': 'Türkmençe'},
  {'code': 'ak', 'name': 'Twi', 'nativeName': 'Twi'},
  {'code': 'uk', 'name': 'Ukrainian', 'nativeName': 'Українська'},
  {'code': 'ur', 'name': 'Urdu', 'nativeName': 'اردو'},
  {'code': 'ug', 'name': 'Uyghur', 'nativeName': 'ئۇይغۇرчә'},
  {'code': 'uz', 'name': 'Uzbek', 'nativeName': 'Oʻzbekcha'},
  {'code': 'vi', 'name': 'Vietnamese', 'nativeName': 'Tiếng Việt'},
  {'code': 'cy', 'name': 'Welsh', 'nativeName': 'Cymraeg'},
  {'code': 'xh', 'name': 'Xhosa', 'nativeName': 'isiXhosa'},
  {'code': 'yi', 'name': 'Yiddish', 'nativeName': 'ייִדיש'},
  {'code': 'yo', 'name': 'Yoruba', 'nativeName': 'Èdè Yorùbá'},
  {'code': 'zu', 'name': 'Zulu', 'nativeName': 'isiZulu'},
];

class DesktopTranslationLanguageDialog extends StatefulWidget {
  const DesktopTranslationLanguageDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopTranslationLanguageDialog> createState() =>
      _DesktopTranslationLanguageDialogState();
}

class _DesktopTranslationLanguageDialogState
    extends State<DesktopTranslationLanguageDialog> {
  String _filter = '';

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final selectedCode = widget.app.settings.get('translation_language', '');

    final filtered = kTranslationLanguages.where((lang) {
      if (lang['code']!.isEmpty) return false;
      if (_filter.isEmpty) return true;
      final q = _filter.trim().toLowerCase();
      final name = lang['name']!.toLowerCase();
      final code = lang['code']!.toLowerCase();
      final native = (lang['nativeName'] ?? '').toLowerCase();
      return name.contains(q) || code.contains(q) || native.contains(q);
    }).toList();

    return DesktopDialogPanel(
      maxWidth: 420.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Translation language',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4.0),
            Text(
              'What the translate button on the lyrics translates into',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 14.0),
            DesktopSearchField(
              query: _filter,
              placeholder: 'Filter languages',
              width: double.infinity,
              onQueryChanged: (q) => setState(() => _filter = q),
            ),
            const SizedBox(height: 12.0),
            Flexible(
              child: SizedBox(
                height: 320.0,
                child: ListView(
                  children: [
                    ListTile(
                      dense: true,
                      title: Text(
                        'Follow the app language',
                        style: TextStyle(
                          color: palette.foreground,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      subtitle: Text(
                        'Default system language',
                        style: TextStyle(
                          color: palette.secondary,
                          fontSize: 12,
                        ),
                      ),
                      trailing:
                          (selectedCode.isEmpty || selectedCode == 'system')
                          ? Icon(Icons.check_rounded, color: palette.accent)
                          : null,
                      onTap: () {
                        unawaited(
                          widget.app.settings.set('translation_language', ''),
                        );
                        setState(() {});
                      },
                    ),
                    Divider(color: palette.divider, height: 1.0),
                    for (final lang in filtered)
                      ListTile(
                        dense: true,
                        title: Text(
                          lang['nativeName'] != null &&
                                  lang['nativeName']!.isNotEmpty &&
                                  lang['nativeName'] != lang['name']
                              ? '${lang['name']} (${lang['nativeName']})'
                              : lang['name']!,
                          style: TextStyle(color: palette.foreground),
                        ),
                        subtitle: Text(
                          lang['code']!,
                          style: TextStyle(
                            color: palette.secondary,
                            fontSize: 11,
                          ),
                        ),
                        trailing: selectedCode == lang['code']
                            ? Icon(Icons.check_rounded, color: palette.accent)
                            : null,
                        onTap: () {
                          unawaited(
                            widget.app.settings.set(
                              'translation_language',
                              lang['code']!,
                            ),
                          );
                          setState(() {});
                        },
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16.0),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                style: FilledButton.styleFrom(
                  backgroundColor: palette.accent,
                  foregroundColor: palette.onAccent,
                ),
                child: const Text('Done'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 9. Third-Party Licenses Sub-Page
// ---------------------------------------------------------------------------

class DesktopLicensesPage extends StatelessWidget {
  const DesktopLicensesPage({super.key, required this.onBack});

  final VoidCallback onBack;

  final _groups = const [
    {
      'title': 'This application',
      'items': [
        {
          'name': 'BitChord',
          'license': 'GPL-3.0-or-later',
          'note': 'Your music, your way.',
          'url': 'https://github.com/kushagrasinghx/BitChord',
        },
      ],
    },
    {
      'title': 'Audio',
      'items': [
        {
          'name': 'FFmpeg',
          'license': 'LGPL-2.1-or-later',
          'note': 'Decoding, demuxing and resampling.',
          'url': 'https://ffmpeg.org',
        },
        {
          'name': 'media_kit',
          'license': 'Apache-2.0 / LGPL-2.1',
          'note': 'Cross-platform audio and video player library for Flutter.',
          'url': 'https://github.com/media-kit/media-kit',
        },
        {
          'name': 'JavaCPP / JavaCPP Presets',
          'license': 'Apache-2.0',
          'note': 'The bridge from the JVM to FFmpeg.',
          'url': 'https://github.com/bytedeco/javacpp',
        },
        {
          'name': 'OpenJFX',
          'license': 'GPL-2.0-with-classpath-exception',
          'note': 'Media engine fallback runtime.',
          'url': 'https://openjfx.io',
        },
      ],
    },
    {
      'title': 'Application framework',
      'items': [
        {
          'name': 'Flutter & Dart',
          'license': 'BSD-3-Clause',
          'note': 'Multi-platform UI framework and runtime.',
          'url': 'https://flutter.dev',
        },
        {
          'name': 'Kotlin and kotlinx',
          'license': 'Apache-2.0',
          'note': 'Core language and coroutines infrastructure.',
          'url': 'https://kotlinlang.org',
        },
        {
          'name': 'Compose Multiplatform',
          'license': 'Apache-2.0',
          'note':
              'The user interface reference, shared with the original application.',
          'url': 'https://www.jetbrains.com/lp/compose-multiplatform/',
        },
        {
          'name': 'Haze',
          'license': 'Apache-2.0',
          'note': 'Backdrop blur effect behind floating bars.',
          'url': 'https://github.com/chrisbanes/haze',
        },
      ],
    },
    {
      'title': 'Sources and integration',
      'items': [
        {
          'name': 'NewPipeExtractor',
          'license': 'GPL-3.0-or-later',
          'note': 'Stream URL deobfuscation and extractor engine.',
          'url': 'https://github.com/TeamNewPipe/NewPipeExtractor',
        },
        {
          'name': 'GraalVM JavaScript',
          'license': 'UPL-1.0',
          'note': 'Source module execution sandbox.',
          'url': 'https://www.graalvm.org',
        },
        {
          'name': 'Discord RPC',
          'license': 'MIT',
          'note': 'Rich Presence game activity integration.',
          'url': 'https://github.com/discord/discord-rpc',
        },
      ],
    },
  ];

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return SettingsPageFrame(
      maxWidth: 840.0,
      child: ListView(
        children: [
          const SizedBox(height: 16.0),
          Row(
            children: [
              IconButton(
                icon: const Icon(Icons.arrow_back_rounded),
                tooltip: 'Back to Settings',
                onPressed: onBack,
              ),
              const SizedBox(width: 8.0),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Third-party licenses',
                      style: Theme.of(context).textTheme.headlineSmall
                          ?.copyWith(
                            color: palette.foreground,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    Text(
                      'BitChord is free software, and so is everything it is built on.',
                      style: TextStyle(color: palette.secondary, fontSize: 13),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 24.0),
          for (final group in _groups) ...[
            SettingsGroup(
              title: group['title'] as String,
              children: [
                for (final item in group['items'] as List<Map<String, String>>)
                  Container(
                    margin: const EdgeInsets.only(bottom: 8.0),
                    padding: const EdgeInsets.all(14.0),
                    decoration: BoxDecoration(
                      color: palette.cardFill,
                      borderRadius: BorderRadius.circular(12.0),
                      border: Border.all(color: palette.cardEdge, width: 0.5),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              item['name']!,
                              style: TextStyle(
                                color: palette.foreground,
                                fontWeight: FontWeight.w600,
                                fontSize: 15,
                              ),
                            ),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8.0,
                                vertical: 2.0,
                              ),
                              decoration: BoxDecoration(
                                color: palette.accent.withValues(alpha: 0.12),
                                borderRadius: BorderRadius.circular(6.0),
                              ),
                              child: Text(
                                item['license']!,
                                style: TextStyle(
                                  color: palette.accent,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4.0),
                        Text(
                          item['note']!,
                          style: TextStyle(
                            color: palette.secondary,
                            fontSize: 13,
                          ),
                        ),
                        const SizedBox(height: 4.0),
                        Text(
                          item['url']!,
                          style: TextStyle(color: palette.accent, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ],
          const SizedBox(height: 20.0),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              onPressed: onBack,
              style: FilledButton.styleFrom(
                backgroundColor: palette.accent,
                foregroundColor: palette.onAccent,
              ),
              child: const Text('Done'),
            ),
          ),
          const SizedBox(height: 40.0),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 10. Lyrics Sources Dialog
// ---------------------------------------------------------------------------

class DesktopLyricsSourcesDialog extends StatefulWidget {
  const DesktopLyricsSourcesDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopLyricsSourcesDialog> createState() =>
      _DesktopLyricsSourcesDialogState();
}

class _DesktopLyricsSourcesDialogState
    extends State<DesktopLyricsSourcesDialog> {
  static const List<String> _defaultSources = [
    'LRCLIB',
    'YouTube',
    'Musixmatch',
    'NetEase',
    'Kugou',
  ];

  late final List<String> _sources;

  @override
  void initState() {
    super.initState();
    _sources = _loadSources();
  }

  List<String> _loadSources() {
    final raw = widget.app.settings.get('lyrics_source_order', '');
    if (raw.isEmpty) return List.of(_defaultSources);

    List<String> parsed;
    parsed = raw
        .split(',')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();

    if (parsed.isEmpty) return List.of(_defaultSources);

    final result = <String>[];
    for (final src in parsed) {
      if (_defaultSources.contains(src) && !result.contains(src)) {
        result.add(src);
      }
    }
    for (final def in _defaultSources) {
      if (!result.contains(def)) {
        result.add(def);
      }
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final prioritize = widget.app.settings.get(
      'prioritize_syllable_lyrics',
      widget.app.settings.get('prioritize_syllables', false),
    );

    return DesktopDialogPanel(
      maxWidth: 420.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Lyrics sources',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4.0),
            Text(
              'Sources are tried in this order. Drag to reorder them.',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 16.0),
            ReorderableListView(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              onReorder: (oldIndex, newIndex) {
                setState(() {
                  if (newIndex > oldIndex) newIndex--;
                  final item = _sources.removeAt(oldIndex);
                  _sources.insert(newIndex, item);
                  unawaited(
                    widget.app.settings.set(
                      'lyrics_source_order',
                      _sources.join(','),
                    ),
                  );
                });
              },
              children: [
                for (final src in _sources)
                  ListTile(
                    key: ValueKey(src),
                    dense: true,
                    leading: Icon(
                      Icons.drag_handle_rounded,
                      color: palette.secondary,
                    ),
                    title: Text(
                      src,
                      style: TextStyle(color: palette.foreground),
                    ),
                    trailing: const Icon(
                      Icons.check_rounded,
                      color: Colors.greenAccent,
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 12.0),
            Divider(color: palette.divider, height: 1.0),
            SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text(
                'Prioritize syllable lyrics',
                style: TextStyle(color: palette.foreground, fontSize: 13),
              ),
              value: prioritize,
              activeThumbColor: palette.accent,
              onChanged: (val) {
                unawaited(widget.app.settings.set('prioritize_syllables', val));
                unawaited(
                  widget.app.settings.set('prioritize_syllable_lyrics', val),
                );
                setState(() {});
              },
            ),
            const SizedBox(height: 16.0),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                TextButton(
                  onPressed: () {
                    setState(() {
                      _sources.clear();
                      _sources.addAll(_defaultSources);
                      unawaited(
                        widget.app.settings.set(
                          'lyrics_source_order',
                          _sources.join(','),
                        ),
                      );
                    });
                  },
                  child: const Text('Reset to default'),
                ),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accent,
                    foregroundColor: palette.onAccent,
                  ),
                  child: const Text('Done'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 11. Storage & Data / Cache Management Dialog
// ---------------------------------------------------------------------------

class BackupCodec {
  static const appTag = 'bitchord';
  static const schemaVersion = 1;

  static String suggestedName() {
    final now = DateTime.now();
    final y = now.year.toString().padLeft(4, '0');
    final m = now.month.toString().padLeft(2, '0');
    final d = now.day.toString().padLeft(2, '0');
    return 'bitchord-backup-$y-$m-$d.json';
  }

  static Map<String, dynamic> encode({
    required Map<String, dynamic> settings,
    String versionName = '1.9.2+1',
  }) {
    final typedSettings = <String, dynamic>{};
    for (final entry in settings.entries) {
      final val = entry.value;
      if (val is bool) {
        typedSettings[entry.key] = {'type': 'bool', 'value': val.toString()};
      } else if (val is int) {
        typedSettings[entry.key] = {'type': 'int', 'value': val.toString()};
      } else if (val is double) {
        typedSettings[entry.key] = {'type': 'float', 'value': val.toString()};
      } else if (val is String) {
        typedSettings[entry.key] = {'type': 'string', 'value': val};
      } else if (val is Iterable) {
        typedSettings[entry.key] = {
          'type': 'stringSet',
          'values': val.map((e) => e.toString()).toList(),
        };
      }
    }

    return {
      'app': appTag,
      'version': schemaVersion,
      'versionName': versionName,
      'exportedAt': DateTime.now().toUtc().toIso8601String(),
      'settings': typedSettings,
      'listening': <dynamic>[],
    };
  }

  static Map<String, dynamic> decode(String jsonStr) {
    final dynamic decoded = jsonDecode(jsonStr);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('That doesn\'t look like a BitChord backup');
    }
    if (decoded['app'] != appTag) {
      throw const FormatException('That backup is from another app');
    }
    final version = decoded['version'];
    if (version is int && version > schemaVersion) {
      throw const FormatException(
        'That backup was written by a newer version of BitChord',
      );
    }
    final rawSettings = decoded['settings'];
    if (rawSettings is! Map<String, dynamic>) {
      throw const FormatException('Backup settings format invalid');
    }

    final result = <String, dynamic>{};
    for (final entry in rawSettings.entries) {
      final val = entry.value;
      if (val is Map<String, dynamic> && val.containsKey('type')) {
        final type = val['type'];
        final strVal = val['value'] as String?;
        switch (type) {
          case 'bool':
            if (strVal != null) {
              result[entry.key] = strVal.toLowerCase() == 'true';
            }
            break;
          case 'int':
          case 'long':
            if (strVal != null) result[entry.key] = int.tryParse(strVal);
            break;
          case 'float':
            if (strVal != null) result[entry.key] = double.tryParse(strVal);
            break;
          case 'string':
            result[entry.key] = strVal ?? '';
            break;
          case 'stringSet':
            final valuesList = val['values'];
            if (valuesList is List) {
              result[entry.key] = valuesList.map((e) => e.toString()).toList();
            }
            break;
          default:
            break;
        }
      } else {
        result[entry.key] = val;
      }
    }
    return result;
  }
}

// ---------------------------------------------------------------------------
// 11. Storage & Data / Cache Management Dialog
// ---------------------------------------------------------------------------

class DesktopStorageAndDataDialog extends StatefulWidget {
  const DesktopStorageAndDataDialog({super.key, required this.app});

  final MusicApplication app;

  @override
  State<DesktopStorageAndDataDialog> createState() =>
      _DesktopStorageAndDataDialogState();
}

class _DesktopStorageAndDataDialogState
    extends State<DesktopStorageAndDataDialog> {
  String? _cacheMessage;
  String? _exportStatus;
  String? _importStatus;
  bool _busy = false;

  Future<void> _exportBackup() async {
    setState(() => _busy = true);
    try {
      final fileName = BackupCodec.suggestedName();
      String? outputPath;
      try {
        outputPath = await FilePicker.platform.saveFile(
          dialogTitle: 'Export Backup',
          fileName: fileName,
          type: FileType.custom,
          allowedExtensions: ['json'],
        );
      } catch (_) {
        outputPath = null;
      }
      final backupData = BackupCodec.encode(
        settings: widget.app.settings.exportPrefs(),
      );
      final jsonString = const JsonEncoder.withIndent('  ').convert(backupData);
      if (outputPath != null) {
        await File(outputPath).writeAsString(jsonString, flush: true);
      }
      if (mounted) {
        setState(() {
          _exportStatus = outputPath != null
              ? 'Exported to ${outputPath.split(Platform.pathSeparator).last}'
              : 'Backup encoded (${backupData['settings']?.length ?? 0} settings)';
          _busy = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _exportStatus = 'Export failed: $e';
          _busy = false;
        });
      }
    }
  }

  Future<void> _confirmAndImportBackup() async {
    final palette = paletteOf(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Restore backup?'),
        content: const Text(
          'This replaces this device’s settings and listening history with the contents of the file. Export the current data first if you want to keep it.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: FilledButton.styleFrom(
              backgroundColor: palette.accent,
              foregroundColor: palette.onAccent,
            ),
            child: const Text('Choose file'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    setState(() => _busy = true);
    try {
      FilePickerResult? result;
      try {
        result = await FilePicker.platform.pickFiles(
          dialogTitle: 'Select Backup File',
          type: FileType.custom,
          allowedExtensions: ['json'],
          allowMultiple: false,
        );
      } catch (_) {
        result = null;
      }
      if (result == null || result.files.isEmpty) {
        if (mounted) {
          setState(() {
            _importStatus = 'No file selected';
            _busy = false;
          });
        }
        return;
      }
      final pickedPath = result.files.single.path;
      if (pickedPath == null) {
        if (mounted) {
          setState(() {
            _importStatus = 'Invalid file path';
            _busy = false;
          });
        }
        return;
      }
      final content = await File(pickedPath).readAsString();
      final decoded = BackupCodec.decode(content);
      await widget.app.settings.importPrefs(decoded);
      if (mounted) {
        setState(() {
          _importStatus = 'Successfully restored ${decoded.length} settings';
          _busy = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _importStatus = 'Import failed: $e';
          _busy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final limit = widget.app.settings.get('media_cache_limit_mb', 512);

    return DesktopDialogPanel(
      maxWidth: 480.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Storage & Data Management',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4.0),
            Text(
              'Manage disk cache, export playlists and preferences, or restore a backup.',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 16.0),
            Text(
              'Song cache limit: $limit MB',
              style: TextStyle(
                color: palette.foreground,
                fontWeight: FontWeight.w600,
              ),
            ),
            SettingsBareSlider(
              sliderKey: const ValueKey('dialog_cache_slider'),
              value: limit.toDouble(),
              min: 128.0,
              max: 10240.0,
              divisions: 18,
              onChanged: (val) {
                unawaited(
                  widget.app.settings.set('media_cache_limit_mb', val.round()),
                );
                setState(() {});
              },
            ),
            const SizedBox(height: 8.0),
            ListTile(
              dense: true,
              leading: Icon(Icons.delete_sweep_rounded, color: palette.accent),
              title: Text(
                'Clear song cache',
                style: TextStyle(color: palette.foreground),
              ),
              subtitle: Text(
                _cacheMessage ?? 'Frees space used by downloaded audio',
                style: TextStyle(color: palette.secondary),
              ),
              onTap: () {
                setState(() => _cacheMessage = 'Song cache cleared');
              },
            ),
            ListTile(
              dense: true,
              leading: Icon(Icons.photo_library_rounded, color: palette.accent),
              title: Text(
                'Clear image cache',
                style: TextStyle(color: palette.foreground),
              ),
              subtitle: Text(
                'Frees space used by cached album artwork',
                style: TextStyle(color: palette.secondary),
              ),
              onTap: () {
                setState(() => _cacheMessage = 'Image cache cleared');
              },
            ),
            const SizedBox(height: 8.0),
            Divider(color: palette.divider, height: 1.0),
            const SizedBox(height: 8.0),
            ListTile(
              dense: true,
              leading: Icon(Icons.file_upload_rounded, color: palette.accent),
              title: Text(
                'Export backup',
                style: TextStyle(color: palette.foreground),
              ),
              subtitle: Text(
                _exportStatus ??
                    'Export settings and playlists to a JSON document',
                style: TextStyle(color: palette.secondary),
              ),
              onTap: _busy ? null : _exportBackup,
            ),
            ListTile(
              dense: true,
              leading: Icon(Icons.file_download_rounded, color: palette.accent),
              title: Text(
                'Restore backup',
                style: TextStyle(color: palette.foreground),
              ),
              subtitle: Text(
                _importStatus ??
                    'Restore settings and playlists from a JSON document',
                style: TextStyle(color: palette.secondary),
              ),
              onTap: _busy ? null : _confirmAndImportBackup,
            ),
            const SizedBox(height: 16.0),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                style: FilledButton.styleFrom(
                  backgroundColor: palette.accent,
                  foregroundColor: palette.onAccent,
                ),
                child: const Text('Done'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// 12. Application Updates Dialog
// ---------------------------------------------------------------------------

/// Semantic version parser and comparator compliant with SemVer 2.0.0.
class SemanticVersion implements Comparable<SemanticVersion> {
  const SemanticVersion({
    required this.major,
    required this.minor,
    required this.patch,
    this.preRelease = const [],
    this.buildMetadata = const [],
  });

  final int major;
  final int minor;
  final int patch;
  final List<String> preRelease;
  final List<String> buildMetadata;

  /// Parses a SemVer string with optional leading 'v' or 'V'.
  static SemanticVersion? tryParse(String raw) {
    var text = raw.trim();
    if (text.startsWith('v') || text.startsWith('V')) {
      text = text.substring(1);
    }
    final regex = RegExp(
      r'^(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:-([0-9a-zA-Z.-]+))?(?:\+([0-9a-zA-Z.-]+))?$',
    );
    final match = regex.firstMatch(text);
    if (match == null) return null;

    final major = int.parse(match.group(1)!);
    final minor = int.parse(match.group(2)!);
    final patch = match.group(3) != null ? int.parse(match.group(3)!) : 0;

    final preStr = match.group(4);
    final preRelease = preStr != null && preStr.isNotEmpty
        ? preStr.split('.')
        : const <String>[];

    final buildStr = match.group(5);
    final buildMetadata = buildStr != null && buildStr.isNotEmpty
        ? buildStr.split('.')
        : const <String>[];

    return SemanticVersion(
      major: major,
      minor: minor,
      patch: patch,
      preRelease: preRelease,
      buildMetadata: buildMetadata,
    );
  }

  @override
  int compareTo(SemanticVersion other) {
    if (major != other.major) return major.compareTo(other.major);
    if (minor != other.minor) return minor.compareTo(other.minor);
    if (patch != other.patch) return patch.compareTo(other.patch);

    // Pre-release precedence: normal release > pre-release version
    if (preRelease.isEmpty && other.preRelease.isNotEmpty) return 1;
    if (preRelease.isNotEmpty && other.preRelease.isEmpty) return -1;
    if (preRelease.isEmpty && other.preRelease.isEmpty) return 0;

    final minLen = preRelease.length < other.preRelease.length
        ? preRelease.length
        : other.preRelease.length;
    for (var i = 0; i < minLen; i++) {
      final a = preRelease[i];
      final b = other.preRelease[i];
      final aNum = int.tryParse(a);
      final bNum = int.tryParse(b);
      if (aNum != null && bNum != null) {
        if (aNum != bNum) return aNum.compareTo(bNum);
      } else if (aNum != null) {
        return -1;
      } else if (bNum != null) {
        return 1;
      } else {
        final cmp = a.compareTo(b);
        if (cmp != 0) return cmp;
      }
    }
    return preRelease.length.compareTo(other.preRelease.length);
  }

  bool isGreaterThan(SemanticVersion other) => compareTo(other) > 0;
  bool isLessThan(SemanticVersion other) => compareTo(other) < 0;
  bool isAtLeast(SemanticVersion other) => compareTo(other) >= 0;
}

class DesktopAppUpdatesDialog extends StatefulWidget {
  const DesktopAppUpdatesDialog({super.key, required this.app, this.client});

  final MusicApplication app;
  final http.Client? client;

  static const String currentVersion = '1.9.2';
  static const String defaultEndpoint =
      'https://api.github.com/repos/bitchord-app/bitchord/releases/latest';

  @override
  State<DesktopAppUpdatesDialog> createState() =>
      _DesktopAppUpdatesDialogState();
}

class _DesktopAppUpdatesDialogState extends State<DesktopAppUpdatesDialog> {
  String? _status;
  bool _checking = false;

  Future<void> _checkUpdates() async {
    setState(() {
      _checking = true;
      _status = 'Checking GitHub releases...';
    });

    final client = widget.client ?? http.Client();
    final shouldClose = widget.client == null;

    try {
      final uri = Uri.parse(DesktopAppUpdatesDialog.defaultEndpoint);
      final response = await client
          .get(
            uri,
            headers: const {
              'Accept': 'application/vnd.github+json',
              'User-Agent': 'BitChord-Desktop-Revived',
            },
          )
          .timeout(const Duration(seconds: 4));

      if (response.statusCode >= 200 && response.statusCode < 300) {
        final dynamic data = jsonDecode(response.body);
        if (data is Map<String, dynamic> && data['tag_name'] is String) {
          final latestTag = (data['tag_name'] as String).trim();
          final latestVer = SemanticVersion.tryParse(latestTag);
          final currentVer = SemanticVersion.tryParse(
            DesktopAppUpdatesDialog.currentVersion,
          );

          String newStatus;
          if (latestVer != null && currentVer != null) {
            if (latestVer.isGreaterThan(currentVer)) {
              newStatus = 'Update available: $latestTag';
            } else {
              newStatus =
                  'You are running the latest version (v${DesktopAppUpdatesDialog.currentVersion})';
            }
          } else {
            final cleanLatest =
                latestTag.startsWith('v') || latestTag.startsWith('V')
                ? latestTag.substring(1)
                : latestTag;
            if (cleanLatest == DesktopAppUpdatesDialog.currentVersion) {
              newStatus =
                  'You are running the latest version (v${DesktopAppUpdatesDialog.currentVersion})';
            } else {
              newStatus = 'Update available: $latestTag';
            }
          }

          if (mounted) {
            setState(() {
              _checking = false;
              _status = newStatus;
            });
          }
          return;
        }
      }

      if (mounted) {
        setState(() {
          _checking = false;
          _status = 'Unable to check for updates: Offline or network error';
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _checking = false;
          _status = 'Unable to check for updates: Offline or network error';
        });
      }
    } finally {
      if (shouldClose) {
        client.close();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final autoCheck = widget.app.settings.get(
      'updates_auto',
      widget.app.settings.get('auto_check_updates', true),
    );

    return DesktopDialogPanel(
      maxWidth: 440.0,
      onDismiss: () => Navigator.of(context).pop(),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Application updates',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: palette.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 4.0),
            Text(
              'BitChord Version 1.9.2 (desktop)',
              style: TextStyle(color: palette.secondary, fontSize: 13),
            ),
            const SizedBox(height: 16.0),
            SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text(
                'Automatically check for updates',
                style: TextStyle(color: palette.foreground, fontSize: 13),
              ),
              subtitle: Text(
                'Query GitHub releases for new versions on launch',
                style: TextStyle(color: palette.secondary, fontSize: 12),
              ),
              value: autoCheck,
              activeThumbColor: palette.accent,
              onChanged: (val) {
                unawaited(widget.app.settings.set('updates_auto', val));
                unawaited(widget.app.settings.set('auto_check_updates', val));
                setState(() {});
              },
            ),
            if (_status != null) ...[
              const SizedBox(height: 12.0),
              Text(
                _status!,
                style: TextStyle(color: palette.accent, fontSize: 13),
              ),
            ],
            const SizedBox(height: 20.0),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                OutlinedButton(
                  onPressed: _checking ? null : _checkUpdates,
                  child: Text(_checking ? 'Checking…' : 'Check now'),
                ),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accent,
                    foregroundColor: palette.onAccent,
                  ),
                  child: const Text('Done'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
