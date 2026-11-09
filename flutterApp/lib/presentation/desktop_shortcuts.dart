import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'desktop_theme.dart';

enum DesktopShortcutAction {
  quickSearch,
  help,
  playPause,
  previous,
  next,
  seekBackward,
  seekForward,
  volumeUp,
  volumeDown,
  shuffle,
  repeat,
  home,
  search,
  lyrics,
}

class DesktopShortcut {
  const DesktopShortcut(
    this.action,
    this.category,
    this.label,
    this.key,
    this.keyLabel, {
    this.control = false,
    this.alt = false,
    this.shift = false,
  });
  final DesktopShortcutAction action;
  final String category, label, keyLabel;
  final LogicalKeyboardKey key;
  final bool control, alt, shift;

  bool matches(KeyEvent event) {
    final keyboard = HardwareKeyboard.instance;
    return event is KeyDownEvent &&
        event.logicalKey == key &&
        (keyboard.isControlPressed || keyboard.isMetaPressed) == control &&
        keyboard.isAltPressed == alt &&
        keyboard.isShiftPressed == shift;
  }

  String get keys => [
    if (control) 'Ctrl / ⌘',
    if (alt) 'Alt',
    if (shift) 'Shift',
    keyLabel,
  ].join(' + ');
}

/// The source's fourteen shortcuts, shared by dispatch, settings and help.
const desktopShortcuts = <DesktopShortcut>[
  DesktopShortcut(
    DesktopShortcutAction.quickSearch,
    'General',
    'Open Quick Search',
    LogicalKeyboardKey.keyK,
    'K',
    control: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.help,
    'General',
    'Keyboard shortcuts',
    LogicalKeyboardKey.slash,
    '/',
    control: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.playPause,
    'Playback',
    'Play / Pause',
    LogicalKeyboardKey.space,
    'Space',
  ),
  DesktopShortcut(
    DesktopShortcutAction.previous,
    'Playback',
    'Previous track',
    LogicalKeyboardKey.arrowLeft,
    '←',
    control: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.next,
    'Playback',
    'Next track',
    LogicalKeyboardKey.arrowRight,
    '→',
    control: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.seekBackward,
    'Playback',
    'Seek backward 5 seconds',
    LogicalKeyboardKey.arrowLeft,
    '←',
    shift: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.seekForward,
    'Playback',
    'Seek forward 5 seconds',
    LogicalKeyboardKey.arrowRight,
    '→',
    shift: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.volumeUp,
    'Playback',
    'Volume up',
    LogicalKeyboardKey.arrowUp,
    '↑',
    alt: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.volumeDown,
    'Playback',
    'Volume down',
    LogicalKeyboardKey.arrowDown,
    '↓',
    alt: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.shuffle,
    'Playback',
    'Shuffle',
    LogicalKeyboardKey.keyS,
    'S',
    alt: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.repeat,
    'Playback',
    'Repeat',
    LogicalKeyboardKey.keyR,
    'R',
    alt: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.home,
    'Navigation',
    'Home',
    LogicalKeyboardKey.keyH,
    'H',
    alt: true,
    shift: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.search,
    'Navigation',
    'Search page',
    LogicalKeyboardKey.keyL,
    'L',
    control: true,
    shift: true,
  ),
  DesktopShortcut(
    DesktopShortcutAction.lyrics,
    'Lyrics',
    'Toggle lyrics',
    LogicalKeyboardKey.keyL,
    'L',
    control: true,
    alt: true,
  ),
];

Future<void> showDesktopShortcuts(BuildContext context) => showDialog<void>(
  context: context,
  builder: (context) => Focus(
    autofocus: true,
    onKeyEvent: (_, event) {
      if (desktopShortcuts[1].matches(event)) {
        Navigator.pop(context);
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored;
    },
    child: AlertDialog(
      title: const Text('Keyboard shortcuts'),
      content: SizedBox(
        width: 520,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 570),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final category in [
                  'General',
                  'Playback',
                  'Navigation',
                  'Lyrics',
                ]) ...[
                  Padding(
                    padding: const EdgeInsets.only(top: 18, bottom: 9),
                    child: Text(
                      category,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  for (final shortcut in desktopShortcuts.where(
                    (item) => item.category == category,
                  ))
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 9),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              shortcut.label,
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          Text(
                            shortcut.keys,
                            style: TextStyle(
                              fontSize: 12,
                              color: DesktopPalette.of(context).secondary,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Done'),
        ),
      ],
    ),
  ),
);
