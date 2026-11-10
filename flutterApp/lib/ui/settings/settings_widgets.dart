import 'package:flutter/material.dart';
import '../theme.dart';

/// Top-level frame centering settings content with desktop max-width and margins.
class SettingsPageFrame extends StatelessWidget {
  const SettingsPageFrame({
    super.key,
    this.maxWidth = 840.0,
    this.padding = const EdgeInsets.symmetric(horizontal: 28.0),
    required this.child,
  });

  final double maxWidth;
  final EdgeInsetsGeometry padding;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: Padding(
          padding: padding,
          child: child,
        ),
      ),
    );
  }
}

/// Categorized group of settings with uppercase header.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({
    super.key,
    required this.title,
    required this.children,
    this.visible = true,
  });

  final String title;
  final List<Widget> children;
  final bool visible;

  @override
  Widget build(BuildContext context) {
    if (!visible) return const SizedBox.shrink();
    final palette = paletteOf(context);

    return Padding(
      padding: const EdgeInsets.only(bottom: 20.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 10.0, bottom: 4.0),
            child: Text(
              title.toUpperCase(),
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: palette.secondary,
                    letterSpacing: 0.8,
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
          ...children,
        ],
      ),
    );
  }
}

/// A standard desktop settings row with a 34x34 icon container, title, subtitle,
/// optional trailing widget or chevron, and hover highlight.
class SettingsRow extends StatefulWidget {
  const SettingsRow({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.onTap,
    this.trailing,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  State<SettingsRow> createState() => _SettingsRowState();
}

class _SettingsRowState extends State<SettingsRow> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final isClickable = widget.onTap != null;

    return MouseRegion(
      cursor: isClickable ? SystemMouseCursors.click : SystemMouseCursors.basic,
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: Container(
          decoration: BoxDecoration(
            color: (_hovered && isClickable) ? palette.rowHover : Colors.transparent,
            borderRadius: BorderRadius.circular(8.0),
          ),
          padding: const EdgeInsets.symmetric(vertical: 14.0, horizontal: 8.0),
          child: Row(
            children: [
              Container(
                width: 34.0,
                height: 34.0,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(9.0),
                  color: palette.foreground.withValues(alpha: 0.08),
                ),
                alignment: Alignment.center,
                child: Icon(widget.icon, size: 19.0, color: palette.accent),
              ),
              const SizedBox(width: 14.0),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      widget.title,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            color: palette.foreground,
                          ),
                    ),
                    if (widget.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2.0),
                      Text(
                        widget.subtitle,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: palette.secondary,
                            ),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              if (widget.trailing != null) ...[
                const SizedBox(width: 10.0),
                widget.trailing!,
              ] else if (isClickable) ...[
                const SizedBox(width: 10.0),
                Icon(Icons.chevron_right_rounded, size: 18.0, color: palette.secondary),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Switch row with title, dynamic subtitle, and whole-row tap toggle.
class SettingsToggle extends StatefulWidget {
  const SettingsToggle({
    super.key,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
    this.enabled = true,
  });

  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool>? onChanged;
  final bool enabled;

  @override
  State<SettingsToggle> createState() => _SettingsToggleState();
}

class _SettingsToggleState extends State<SettingsToggle> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return MouseRegion(
      cursor: widget.enabled ? SystemMouseCursors.click : SystemMouseCursors.basic,
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.enabled ? () => widget.onChanged?.call(!widget.value) : null,
        child: Container(
          decoration: BoxDecoration(
            color: (_hovered && widget.enabled) ? palette.rowHover : Colors.transparent,
            borderRadius: BorderRadius.circular(8.0),
          ),
          padding: const EdgeInsets.symmetric(vertical: 13.0, horizontal: 8.0),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      widget.title,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            color: widget.enabled ? palette.foreground : palette.secondary,
                          ),
                    ),
                    if (widget.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2.0),
                      Text(
                        widget.subtitle,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: palette.secondary,
                            ),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 18.0),
              Switch(
                value: widget.value,
                onChanged: widget.enabled ? widget.onChanged : null,
                activeColor: palette.accent,
                activeTrackColor: palette.accent.withValues(alpha: 0.5),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Navigation row leading to a sub-page or modal dialog with trailing chevron.
class SettingsNavigationRow extends StatefulWidget {
  const SettingsNavigationRow({
    super.key,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  State<SettingsNavigationRow> createState() => _SettingsNavigationRowState();
}

class _SettingsNavigationRowState extends State<SettingsNavigationRow> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: Container(
          decoration: BoxDecoration(
            color: _hovered ? palette.rowHover : Colors.transparent,
            borderRadius: BorderRadius.circular(8.0),
          ),
          padding: const EdgeInsets.symmetric(vertical: 12.0, horizontal: 8.0),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      widget.title,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                            color: palette.foreground,
                          ),
                    ),
                    if (widget.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2.0),
                      Text(
                        widget.subtitle,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: palette.secondary,
                            ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 10.0),
              Icon(Icons.chevron_right_rounded, size: 18.0, color: palette.secondary),
            ],
          ),
        ),
      ),
    );
  }
}

/// Clean desktop slider without tick marks or end-stop dots.
class SettingsBareSlider extends StatelessWidget {
  const SettingsBareSlider({
    Key? key,
    this.sliderKey,
    required this.value,
    required this.onChanged,
    this.onChangeEnd,
    this.min = 0.0,
    this.max = 1.0,
    this.divisions,
  }) : super(key: key);

  final Key? sliderKey;
  final double value;
  final ValueChanged<double> onChanged;
  final ValueChanged<double>? onChangeEnd;
  final double min;
  final double max;
  final int? divisions;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return SliderTheme(
      data: SliderTheme.of(context).copyWith(
        trackHeight: 4.0,
        thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7.0),
        overlayShape: const RoundSliderOverlayShape(overlayRadius: 14.0),
        activeTrackColor: palette.accent,
        inactiveTrackColor: palette.accent.withValues(alpha: 0.24),
        thumbColor: palette.accent,
        tickMarkShape: SliderTickMarkShape.noTickMark,
      ),
      child: Slider(
        key: sliderKey,
        value: value.clamp(min, max),
        min: min,
        max: max,
        divisions: divisions,
        onChanged: onChanged,
        onChangeEnd: onChangeEnd,
      ),
    );
  }
}

/// Generic model for chip and segmented pill selection.
class FilterChipItem<T> {
  const FilterChipItem({required this.value, required this.label});
  final T value;
  final String label;
}

/// Desktop horizontal chip selector with active pill styling.
class FilterChipGroup<T> extends StatelessWidget {
  const FilterChipGroup({
    super.key,
    required this.items,
    required this.selected,
    required this.onSelected,
  });

  final List<FilterChipItem<T>> items;
  final T selected;
  final ValueChanged<T> onSelected;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return Wrap(
      spacing: 8.0,
      runSpacing: 8.0,
      children: items.map((item) {
        final isSelected = item.value == selected;
        return FilterChip(
          selected: isSelected,
          label: Text(item.label),
          onSelected: (_) => onSelected(item.value),
          showCheckmark: false,
          selectedColor: palette.accent.withValues(alpha: 0.16),
          backgroundColor: palette.surfaceRaised,
          side: BorderSide(
            color: isSelected ? palette.accent : palette.divider,
            width: 1.0,
          ),
          labelStyle: TextStyle(
            color: isSelected ? palette.foreground : palette.secondary,
            fontWeight: isSelected ? FontWeight.w600 : FontWeight.w500,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8.0)),
        );
      }).toList(),
    );
  }
}

/// Pill capsule selector for mode choices.
class SettingsSegmentedControl<T> extends StatelessWidget {
  const SettingsSegmentedControl({
    super.key,
    required this.items,
    required this.selected,
    required this.onSelected,
  });

  final List<FilterChipItem<T>> items;
  final T selected;
  final ValueChanged<T> onSelected;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(24.0),
        color: isDark ? Colors.white.withValues(alpha: 0.10) : Colors.black.withValues(alpha: 0.08),
      ),
      padding: const EdgeInsets.all(4.0),
      child: Row(
        children: items.map((item) {
          final isChosen = item.value == selected;
          return Expanded(
            child: GestureDetector(
              onTap: () => onSelected(item.value),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                padding: const EdgeInsets.symmetric(vertical: 10.0),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(20.0),
                  color: isChosen
                      ? (isDark ? Colors.white.withValues(alpha: 0.24) : Colors.white)
                      : Colors.transparent,
                ),
                alignment: Alignment.center,
                child: Text(
                  item.label,
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: palette.foreground,
                        fontWeight: isChosen ? FontWeight.w600 : FontWeight.w500,
                      ),
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

/// Header search field filtering settings sections and items in real-time.
class DesktopSearchField extends StatefulWidget {
  const DesktopSearchField({
    super.key,
    required this.query,
    required this.onQueryChanged,
    this.placeholder = 'Search settings',
    this.width = 280.0,
  });

  final String query;
  final ValueChanged<String> onQueryChanged;
  final String placeholder;
  final double width;

  @override
  State<DesktopSearchField> createState() => _DesktopSearchFieldState();
}

class _DesktopSearchFieldState extends State<DesktopSearchField> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.query);
  }

  @override
  void didUpdateWidget(DesktopSearchField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.query != _controller.text) {
      _controller.text = widget.query;
      _controller.selection = TextSelection.fromPosition(
        TextPosition(offset: _controller.text.length),
      );
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return SizedBox(
      width: widget.width,
      height: 38.0,
      child: TextField(
        controller: _controller,
        onChanged: widget.onQueryChanged,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: palette.foreground),
        decoration: InputDecoration(
          hintText: widget.placeholder,
          hintStyle: Theme.of(context).textTheme.bodyMedium?.copyWith(color: palette.secondary),
          prefixIcon: Icon(Icons.search_rounded, size: 18.0, color: palette.secondary),
          suffixIcon: widget.query.isNotEmpty
              ? IconButton(
                  icon: Icon(Icons.close_rounded, size: 16.0, color: palette.secondary),
                  padding: EdgeInsets.zero,
                  onPressed: () {
                    _controller.clear();
                    widget.onQueryChanged('');
                  },
                )
              : null,
          filled: true,
          fillColor: palette.searchSurface,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12.0, vertical: 0.0),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(10.0),
            borderSide: BorderSide(color: palette.barEdge, width: 0.5),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(10.0),
            borderSide: BorderSide(color: palette.barEdge, width: 0.5),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(10.0),
            borderSide: BorderSide(color: palette.accent, width: 1.0),
          ),
        ),
      ),
    );
  }
}

/// App icon picker with 64x64 preview cards.
class AppIconPicker extends StatelessWidget {
  const AppIconPicker({
    super.key,
    required this.selected,
    required this.onSelected,
  });

  final String selected;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    final palette = paletteOf(context);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8.0),
      child: Row(
        children: [
          _buildIconCard(context, palette, 'DARK', 'Dark', 'assets/icons/app_icon.png'),
          const SizedBox(width: 12.0),
          _buildIconCard(context, palette, 'LIGHT', 'Light', 'assets/icons/app_icon_light.png'),
        ],
      ),
    );
  }

  Widget _buildIconCard(BuildContext context, AppColors palette, String value, String label, String assetPath) {
    final isChosen = selected == value;
    return GestureDetector(
      onTap: () => onSelected(value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 22.0, vertical: 14.0),
        decoration: BoxDecoration(
          color: isChosen ? palette.subtleFill : palette.surface,
          borderRadius: BorderRadius.circular(16.0),
          border: Border.all(
            color: isChosen ? palette.accent : palette.divider,
            width: isChosen ? 1.5 : 1.0,
          ),
        ),
        child: Column(
          children: [
            Image.asset(
              assetPath,
              width: 64.0,
              height: 64.0,
              errorBuilder: (_, __, ___) => const Icon(Icons.music_note_rounded, size: 64.0),
            ),
            const SizedBox(height: 10.0),
            Text(
              label,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: palette.foreground,
                    fontWeight: isChosen ? FontWeight.w600 : FontWeight.w500,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}
