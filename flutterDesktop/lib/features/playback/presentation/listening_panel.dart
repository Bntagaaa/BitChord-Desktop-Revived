import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../application/listening_controller.dart';
import '../domain/listening_settings.dart';
import 'sleep_panel.dart';

// Measured from DesktopEqualizerUi.kt at 348b613a: 560-dp dialog,
// 22-dp body inset, 200-dp 11x11 tone pad and 30x150-dp signed faders.
// Native-window/motion/DPI parity remains a separate acceptance gate.
enum ListeningSection { all, effects, transport }

class ListeningPanel extends ConsumerWidget {
  const ListeningPanel({super.key, this.section = ListeningSection.all});
  final ListeningSection section;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(listeningProvider);
    if (!view.available && section == ListeningSection.all)
      return const SizedBox.shrink();
    Widget unavailable(String title) => Opacity(
      opacity: .38,
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        enabled: false,
        title: Text(title),
        subtitle: const Text('Unavailable'),
        trailing: const Icon(Icons.chevron_right, size: 20),
      ),
    );
    if (!view.available)
      return Column(
        children: [
          for (final title
              in section == ListeningSection.transport
                  ? ['Playback speed', 'Sleep timer']
                  : ['Skip silence', 'Spatial audio', 'Equalizer'])
            unavailable(title),
        ],
      );
    Widget setting(String title, String subtitle, Widget control) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 13),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title),
                Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
          const SizedBox(width: 18),
          control,
        ],
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (section == ListeningSection.all) ...[
          const SizedBox(height: 24),
          Text('Audio', style: Theme.of(context).textTheme.titleLarge),
        ],
        if (section != ListeningSection.transport) ...[
          if (view.tempoAvailable)
            setting(
              'Skip silence',
              'Shorten long gaps rather than playing them out',
              _Toggle(
                label: 'Skip silence',
                value: view.settings.skipSilence,
                onChanged: (skip) => ref
                    .read(listeningProvider.notifier)
                    .update(view.settings.copyWith(skipSilence: skip)),
              ),
            ),
          setting(
            section == ListeningSection.all
                ? 'Stereo widening'
                : 'Spatial audio',
            'Widens stereo tracks for a more immersive feel',
            _Toggle(
              label: 'Stereo widening',
              value: view.settings.spatial,
              onChanged: (value) => ref
                  .read(listeningProvider.notifier)
                  .update(view.settings.copyWith(spatial: value)),
            ),
          ),
          InkWell(
            onTap: () => showDialog<void>(
              context: context,
              builder: (_) => const _EqualizerDialog(),
            ),
            child: setting(
              'Equalizer',
              view.settings.enabled ? 'Tone, seven bands and balance' : 'Off',
              const Icon(Icons.chevron_right, size: 20),
            ),
          ),
        ],
        if (section != ListeningSection.effects) ...[
          if (view.tempoAvailable)
            setting(
              'Playback speed',
              'Tap to change',
              _Choice(
                label: '${view.settings.speed.toStringAsFixed(2)}×',
                selected: false,
                onTap: () {
                  final speed = view.settings.speed;
                  final next = speed < .76
                      ? 1.0
                      : speed < 1.01
                      ? 1.25
                      : speed < 1.26
                      ? 1.5
                      : speed < 1.51
                      ? 2.0
                      : .5;
                  ref
                      .read(listeningProvider.notifier)
                      .update(view.settings.copyWith(speed: next));
                },
              ),
            ),
          const SleepPanel(),
        ],
        if (view.error != null) Text(view.error!),
      ],
    );
  }
}

class _EqualizerDialog extends ConsumerWidget {
  const _EqualizerDialog();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(listeningProvider), settings = view.settings;
    void update(ListeningSettings next) =>
        ref.read(listeningProvider.notifier).update(next);
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(22, 18, 22, 14),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Equalizer',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                          const Text(
                            'Shapes the sound inside BitChord, before it reaches the system.',
                          ),
                        ],
                      ),
                    ),
                    _Toggle(
                      label: 'Enable equalizer',
                      value: settings.enabled,
                      onChanged: (value) =>
                          update(settings.copyWith(enabled: value)),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                AnimatedOpacity(
                  opacity: settings.enabled ? 1 : .38,
                  duration: const Duration(milliseconds: 150),
                  child: IgnorePointer(
                    ignoring: !settings.enabled,
                    child: ExcludeFocus(
                      excluding: !settings.enabled,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              for (final mode in [
                                EqualizerMode.dynamic,
                                EqualizerMode.manual,
                              ])
                                Expanded(
                                  child: _Choice(
                                    label: mode == EqualizerMode.dynamic
                                        ? 'Dynamic'
                                        : 'Manual',
                                    selected: settings.mode == mode,
                                    onTap: () =>
                                        update(settings.copyWith(mode: mode)),
                                  ),
                                ),
                            ],
                          ),
                          const SizedBox(height: 18),
                          if (settings.mode == EqualizerMode.dynamic) ...[
                            const Text('TONE'),
                            _TonePad(
                              x: settings.toneX,
                              y: settings.toneY,
                              onChanged: (x, y) =>
                                  update(settings.copyWith(toneX: x, toneY: y)),
                            ),
                            Center(
                              child: Text(
                                'Tilt ${settings.toneX}       Contour ${settings.toneY}',
                              ),
                            ),
                            Row(
                              children: [
                                for (final focused in [false, true])
                                  _Choice(
                                    label: focused ? 'Focused' : 'Broad',
                                    selected: settings.focused == focused,
                                    onTap: () => update(
                                      settings.copyWith(focused: focused),
                                    ),
                                  ),
                              ],
                            ),
                            const Text(
                              'Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward.',
                            ),
                          ] else ...[
                            const Text('BANDS'),
                            LayoutBuilder(
                              builder: (context, constraints) {
                                final faders = Row(
                                  mainAxisAlignment:
                                      MainAxisAlignment.spaceBetween,
                                  children: [
                                    for (var i = 0; i < 7; i++)
                                      Column(
                                        children: [
                                          Text(
                                            settings.bands[i].toStringAsFixed(
                                              1,
                                            ),
                                          ),
                                          _Fader(
                                            label: '${_frequencies[i]} Hz',
                                            value: settings.bands[i],
                                            onChanged: (value) {
                                              final bands = settings.bands
                                                  .toList();
                                              bands[i] = value;
                                              update(
                                                settings.copyWith(bands: bands),
                                              );
                                            },
                                          ),
                                          Text(
                                            _frequencies[i],
                                            style: Theme.of(
                                              context,
                                            ).textTheme.bodySmall,
                                          ),
                                        ],
                                      ),
                                  ],
                                );
                                final presets = SizedBox(
                                  width: 168,
                                  height: 186,
                                  child: ListView(
                                    children: [
                                      for (final preset in _presets.entries)
                                        _Choice(
                                          label: preset.key,
                                          selected: List.generate(
                                            7,
                                            (i) =>
                                                (settings.bands[i] -
                                                        preset.value[i])
                                                    .abs() <
                                                .05,
                                          ).every((v) => v),
                                          onTap: () => update(
                                            settings.copyWith(
                                              bands: preset.value,
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                );
                                return constraints.maxWidth >= 450
                                    ? Row(
                                        children: [
                                          Expanded(child: faders),
                                          const SizedBox(width: 14),
                                          presets,
                                        ],
                                      )
                                    : Column(children: [faders, presets]);
                              },
                            ),
                            TextButton(
                              onPressed: () => update(
                                settings.copyWith(bands: List.filled(7, 0)),
                              ),
                              child: const Text('Reset all bands'),
                            ),
                          ],
                          const SizedBox(height: 18),
                          const Text('BALANCE'),
                          Row(
                            children: [
                              const Text('L'),
                              Expanded(
                                child: _Balance(
                                  value: settings.balance,
                                  onChanged: (value) =>
                                      update(settings.copyWith(balance: value)),
                                ),
                              ),
                              const Text('R'),
                            ],
                          ),
                          const Text(
                            'Shifts the sound towards one ear by quietening the other.',
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                if (view.error != null) Text(view.error!),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Done'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Toggle extends StatelessWidget {
  const _Toggle({
    required this.label,
    required this.value,
    required this.onChanged,
  });
  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;
  @override
  Widget build(BuildContext context) => Semantics(
    label: label,
    toggled: value,
    child: InkWell(
      onTap: () => onChanged(!value),
      borderRadius: BorderRadius.circular(20),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        width: 44,
        height: 26,
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          color: value
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.onSurface.withValues(alpha: .2),
        ),
        child: Align(
          alignment: value ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            width: 20,
            height: 20,
            decoration: const BoxDecoration(
              color: Colors.white,
              shape: BoxShape.circle,
            ),
          ),
        ),
      ),
    ),
  );
}

class _Choice extends StatelessWidget {
  const _Choice({
    required this.label,
    required this.selected,
    required this.onTap,
  });
  final String label;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Semantics(
    selected: selected,
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(7),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(7),
          color: selected
              ? Theme.of(context).colorScheme.primary.withValues(alpha: .2)
              : Colors.transparent,
        ),
        child: Text(label),
      ),
    ),
  );
}

class _TonePad extends StatelessWidget {
  const _TonePad({required this.x, required this.y, required this.onChanged});
  final int x, y;
  final void Function(int, int) onChanged;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      void point(Offset p) => onChanged(
        (((p.dx - 16) / (constraints.maxWidth - 32)).clamp(0, 1) * 10).round() -
            5,
        5 - (((p.dy - 16) / 168).clamp(0, 1) * 10).round(),
      );
      return Semantics(
        label: 'Tone pad',
        value: 'Tilt $x, Contour $y',
        child: Focus(
          onKeyEvent: (_, event) {
            if (event is! KeyDownEvent && event is! KeyRepeatEvent)
              return KeyEventResult.ignored;
            final k = event.logicalKey;
            if (k == LogicalKeyboardKey.arrowLeft ||
                k == LogicalKeyboardKey.arrowRight) {
              onChanged(
                (x + (k == LogicalKeyboardKey.arrowRight ? 1 : -1)).clamp(
                  -5,
                  5,
                ),
                y,
              );
              return KeyEventResult.handled;
            }
            if (k == LogicalKeyboardKey.arrowUp ||
                k == LogicalKeyboardKey.arrowDown) {
              onChanged(
                x,
                (y + (k == LogicalKeyboardKey.arrowUp ? 1 : -1)).clamp(-5, 5),
              );
              return KeyEventResult.handled;
            }
            return KeyEventResult.ignored;
          },
          child: Builder(
            builder: (context) => GestureDetector(
              onTapDown: (e) {
                Focus.of(context).requestFocus();
                point(e.localPosition);
              },
              onPanUpdate: (e) => point(e.localPosition),
              child: CustomPaint(
                size: Size(constraints.maxWidth, 200),
                painter: _TonePainter(
                  x,
                  y,
                  Theme.of(context).colorScheme.onSurface,
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

class _TonePainter extends CustomPainter {
  _TonePainter(this.x, this.y, this.foreground);
  final int x, y;
  final Color foreground;
  @override
  void paint(Canvas canvas, Size size) {
    for (var col = 0; col <= 10; col++)
      for (var row = 0; row <= 10; row++) {
        canvas.drawCircle(
          Offset(16 + (size.width - 32) * col / 10, 16 + 168 * row / 10),
          2.2,
          Paint()
            ..color = foreground.withValues(
              alpha: (col == 5 || row == 5) ? .45 : .22,
            ),
        );
      }
    canvas.drawCircle(
      Offset(16 + (size.width - 32) * (x + 5) / 10, 16 + 168 * (5 - y) / 10),
      16,
      Paint()..color = foreground,
    );
  }

  @override
  bool shouldRepaint(_TonePainter old) =>
      x != old.x || y != old.y || foreground != old.foreground;
}

class _Fader extends StatelessWidget {
  const _Fader({
    required this.label,
    required this.value,
    required this.onChanged,
  });
  final String label;
  final double value;
  final ValueChanged<double> onChanged;
  @override
  Widget build(BuildContext context) {
    void set(double value) => onChanged(value.clamp(-12, 12));
    void point(Offset p) {
      final raw = (1 - ((p.dy - 9) / 132).clamp(0, 1) * 2) * 12;
      set(raw.abs() < .6 ? 0 : (raw * 2).round() / 2);
    }

    return Semantics(
      label: label,
      value: '$value dB',
      increasedValue: '${(value + .5).clamp(-12, 12)} dB',
      decreasedValue: '${(value - .5).clamp(-12, 12)} dB',
      onIncrease: () => set(value + .5),
      onDecrease: () => set(value - .5),
      child: Focus(
        onKeyEvent: (_, event) {
          if (event is KeyDownEvent || event is KeyRepeatEvent) {
            if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
              set(value + .5);
              return KeyEventResult.handled;
            }
            if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
              set(value - .5);
              return KeyEventResult.handled;
            }
          }
          return KeyEventResult.ignored;
        },
        child: Builder(
          builder: (context) => GestureDetector(
            onTapDown: (e) {
              Focus.of(context).requestFocus();
              point(e.localPosition);
            },
            onPanUpdate: (e) => point(e.localPosition),
            child: CustomPaint(
              size: const Size(30, 150),
              painter: _FaderPainter(
                value,
                Theme.of(context).colorScheme.onSurface,
                Theme.of(context).colorScheme.primary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _FaderPainter extends CustomPainter {
  _FaderPainter(this.value, this.foreground, this.accent);
  final double value;
  final Color foreground, accent;
  @override
  void paint(Canvas canvas, Size size) {
    final y = 9 + 132 * (1 - (value / 12 + 1) / 2);
    canvas.drawLine(
      const Offset(15, 9),
      const Offset(15, 141),
      Paint()
        ..color = foreground.withValues(alpha: .18)
        ..strokeWidth = 5
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      const Offset(15, 75),
      Offset(15, y),
      Paint()
        ..color = accent
        ..strokeWidth = 5
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      const Offset(11, 75),
      const Offset(19, 75),
      Paint()..color = foreground.withValues(alpha: .35),
    );
    canvas.drawCircle(Offset(15, y), 9, Paint()..color = foreground);
  }

  @override
  bool shouldRepaint(_FaderPainter old) =>
      value != old.value ||
      foreground != old.foreground ||
      accent != old.accent;
}

class _Balance extends StatelessWidget {
  const _Balance({required this.value, required this.onChanged});
  final double value;
  final ValueChanged<double> onChanged;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      void set(double next) => onChanged(next.clamp(-1, 1));
      void point(Offset p) {
        final raw =
            ((p.dx - 9) / (constraints.maxWidth - 18)).clamp(0, 1) * 2 - 1;
        set(raw.abs() < .04 ? 0 : raw.toDouble());
      }

      return Semantics(
        label: 'Balance',
        value: value.toStringAsFixed(2),
        increasedValue: (value + .05).clamp(-1, 1).toStringAsFixed(2),
        decreasedValue: (value - .05).clamp(-1, 1).toStringAsFixed(2),
        onIncrease: () => set(value + .05),
        onDecrease: () => set(value - .05),
        child: Focus(
          onKeyEvent: (_, event) {
            if (event is KeyDownEvent || event is KeyRepeatEvent) {
              if (event.logicalKey == LogicalKeyboardKey.arrowRight) {
                set(value + .05);
                return KeyEventResult.handled;
              }
              if (event.logicalKey == LogicalKeyboardKey.arrowLeft) {
                set(value - .05);
                return KeyEventResult.handled;
              }
            }
            return KeyEventResult.ignored;
          },
          child: Builder(
            builder: (context) => GestureDetector(
              onTapDown: (e) {
                Focus.of(context).requestFocus();
                point(e.localPosition);
              },
              onPanUpdate: (e) => point(e.localPosition),
              child: CustomPaint(
                size: Size(constraints.maxWidth, 26),
                painter: _BalancePainter(
                  value,
                  Theme.of(context).colorScheme.onSurface,
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

class _BalancePainter extends CustomPainter {
  _BalancePainter(this.value, this.foreground);
  final double value;
  final Color foreground;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawLine(
      const Offset(9, 13),
      Offset(size.width - 9, 13),
      Paint()
        ..color = foreground.withValues(alpha: .18)
        ..strokeWidth = 5
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      Offset(size.width / 2, 8),
      Offset(size.width / 2, 18),
      Paint()..color = foreground.withValues(alpha: .35),
    );
    canvas.drawCircle(
      Offset(9 + (size.width - 18) * (value + 1) / 2, 13),
      9,
      Paint()..color = foreground,
    );
  }

  @override
  bool shouldRepaint(_BalancePainter old) =>
      value != old.value || foreground != old.foreground;
}

const _frequencies = ['60', '150', '400', '1k', '2.5k', '6k', '14k'];
const _presets = <String, List<double>>{
  'Flat': [0, 0, 0, 0, 0, 0, 0],
  'Acoustic': [3, 1.5, 0, 1.5, 2.5, 2, 1],
  'Bass boost': [6, 4, 1.5, 0, 0, 0, 0],
  'Bass cut': [-6, -4, -1.5, 0, 0, 0, 0],
  'Vocal': [-3, -1.5, 1, 3.5, 3, 1, -1],
  'Treble boost': [0, 0, 0, 0, 1.5, 3.5, 5],
  'Treble cut': [0, 0, 0, 0, -1.5, -3.5, -5],
  'Loudness': [6, 3.5, 0, -1.5, -1, 2, 5],
  'Spoken word': [-5, -2.5, 1.5, 4, 3.5, 1.5, -2],
  'Electronic': [5, 3, -1, 0, 1, 3, 4],
  'Rock': [4, 2.5, -1, -1.5, 1, 3, 3.5],
  'Hip hop': [6, 4, .5, -1, .5, 2, 2.5],
  'Jazz': [3, 1.5, 0, 1, 1.5, 2, 2.5],
  'Classical': [3, 2, 0, 0, 1, 2.5, 3],
  'Small speakers': [5, 4, 2, .5, 0, -1, -2],
  'Late night': [3, 1, 0, 1.5, 1, -1, -3],
};
