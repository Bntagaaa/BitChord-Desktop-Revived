import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../application/search_suggestions.dart';
import '../../../design_system/palette.dart';

// DesktopApp.kt:DesktopSearchField(pill=true) and SearchScreen.kt:SuggestionRow.
// Keystrokes use music/get_search_suggestions, never authenticated full search.
class CatalogSearchField extends ConsumerStatefulWidget {
  const CatalogSearchField({
    super.key,
    required this.controller,
    required this.youtube,
    required this.onSubmit,
    required this.onEdit,
  });
  final TextEditingController controller;
  final bool youtube;
  final ValueChanged<String> onSubmit;
  final VoidCallback onEdit;
  @override
  ConsumerState<CatalogSearchField> createState() => _CatalogSearchFieldState();
}

class _CatalogSearchFieldState extends ConsumerState<CatalogSearchField> {
  final _focus = FocusNode();
  bool _typing = false;
  int _selected = -1;
  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  void _edit(String text) {
    setState(() {
      _typing = text.trim().isNotEmpty;
      _selected = -1;
    });
    widget.onEdit();
    ref
        .read(searchSuggestionsProvider.notifier)
        .edit(text, youtube: widget.youtube);
  }

  void _dismiss() {
    setState(() {
      _typing = false;
      _selected = -1;
    });
    ref.read(searchSuggestionsProvider.notifier).dismiss();
  }

  void _submit(String text) {
    if (text.trim().isEmpty) return;
    widget.controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
    _dismiss();
    _focus.unfocus();
    widget.onSubmit(text);
  }

  @override
  Widget build(BuildContext context) {
    final suggestions = ref.watch(searchSuggestionsProvider);
    final rows = _typing && widget.youtube
        ? [widget.controller.text, ...suggestions]
        : <String>[];
    final colors = Theme.of(context).colorScheme;
    final palette = Theme.of(context).brightness == Brightness.dark
        ? BitChordPalette.dark
        : BitChordPalette.light;
    return TextFieldTapRegion(
      child: Focus(
        onKeyEvent: (_, event) {
          if (event is! KeyDownEvent) return KeyEventResult.ignored;
          if (event.logicalKey == LogicalKeyboardKey.escape) {
            _dismiss();
            return KeyEventResult.handled;
          }
          if (rows.isNotEmpty &&
              [
                LogicalKeyboardKey.arrowDown,
                LogicalKeyboardKey.arrowUp,
              ].contains(event.logicalKey)) {
            setState(
              () => _selected =
                  (_selected +
                          (event.logicalKey == LogicalKeyboardKey.arrowDown
                              ? 1
                              : -1))
                      .clamp(0, rows.length - 1),
            );
            return KeyEventResult.handled;
          }
          if (event.logicalKey == LogicalKeyboardKey.enter) {
            _submit(
              _selected >= 0 && _selected < rows.length
                  ? rows[_selected]
                  : widget.controller.text,
            );
            return KeyEventResult.handled;
          }
          return KeyEventResult.ignored;
        },
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 18, bottom: 14),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 540),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Container(
                      key: const Key('catalog-search-pill'),
                      height: 42,
                      padding: const EdgeInsets.symmetric(horizontal: 9),
                      decoration: BoxDecoration(
                        color: palette['searchSurface'],
                        borderRadius: BorderRadius.circular(21),
                        border: Border.all(
                          color: colors.onSurface.withValues(alpha: .10),
                        ),
                      ),
                      child: Row(
                        children: [
                          SizedBox(
                            width: 24,
                            height: 24,
                            child: IconButton(
                              tooltip: 'Search',
                              padding: const EdgeInsets.all(3),
                              onPressed: () => _submit(widget.controller.text),
                              icon: SvgPicture.asset(
                                'assets/icons/TabSearch.svg',
                                colorFilter: ColorFilter.mode(
                                  colors.onSurfaceVariant,
                                  BlendMode.srcIn,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: TextField(
                              key: const Key('catalog-search-input'),
                              controller: widget.controller,
                              focusNode: _focus,
                              maxLines: 1,
                              style: Theme.of(context).textTheme.bodyMedium,
                              decoration: const InputDecoration(
                                hintText: 'Artists, Songs, Lyrics, and More',
                                isDense: true,
                                border: InputBorder.none,
                                enabledBorder: InputBorder.none,
                                focusedBorder: InputBorder.none,
                                contentPadding: EdgeInsets.zero,
                              ),
                              onChanged: _edit,
                              onSubmitted: (_) =>
                                  _submit(widget.controller.text),
                              onTapOutside: (_) {
                                _dismiss();
                                _focus.unfocus();
                              },
                            ),
                          ),
                          if (widget.controller.text.isNotEmpty)
                            SizedBox(
                              width: 28,
                              height: 28,
                              child: IconButton(
                                padding: EdgeInsets.zero,
                                tooltip: 'Clear search',
                                iconSize: 17,
                                icon: const Icon(Icons.close),
                                onPressed: () {
                                  widget.controller.clear();
                                  _edit('');
                                  _focus.requestFocus();
                                },
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
            if (rows.isNotEmpty)
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 240),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: rows.length,
                  itemBuilder: (context, index) => Material(
                    color: Colors.transparent,
                    child: InkWell(
                      key: ValueKey('suggestion-$index'),
                      borderRadius: BorderRadius.circular(8),
                      onTap: () => _submit(rows[index]),
                      child: Container(
                        color: _selected == index
                            ? colors.onSurface.withValues(alpha: .075)
                            : null,
                        padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
                        child: Row(
                          children: [
                            Icon(
                              Icons.search_rounded,
                              size: 22,
                              color: colors.onSurfaceVariant,
                            ),
                            const SizedBox(width: 16),
                            Expanded(
                              child: Text(
                                index == 0
                                    ? 'Search "${rows[index]}"'
                                    : rows[index],
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                            ),
                            if (index > 0)
                              IconButton(
                                tooltip: 'Fill search',
                                icon: const Icon(Icons.north_west, size: 20),
                                onPressed: () {
                                  widget.controller.value = TextEditingValue(
                                    text: rows[index],
                                    selection: TextSelection.collapsed(
                                      offset: rows[index].length,
                                    ),
                                  );
                                  _edit(rows[index]);
                                  _focus.requestFocus();
                                },
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
