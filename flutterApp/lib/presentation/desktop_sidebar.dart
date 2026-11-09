import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'desktop_theme.dart';

enum DesktopDestination {
  listenNow('listen-now', 'Home'),
  newPage('new', 'New'),
  explore('explore', 'Explore'),
  libraryPage('library', 'Library'),
  spotify('spotify', 'Spotify'),
  search('search', 'Search'),
  history('history', 'History'),
  downloads('downloads', 'Downloads'),
  localMusic('local-music', 'Local Music'),
  settings('settings', 'Settings');

  final String id;
  final String label;
  const DesktopDestination(this.id, this.label);
}

/// Translated 1:1 from DesktopApp.kt lines 5789-6050 (DesktopSidebar).
class DesktopSidebar extends StatelessWidget {
  final bool collapsed;
  final VoidCallback onToggleCollapse;
  final DesktopDestination destination;
  final bool settingsOpen;
  final ValueChanged<DesktopDestination> onDestinationSelected;
  final VoidCallback onOpenSettings;
  final DesktopThemePalette palette;

  const DesktopSidebar({
    super.key,
    required this.collapsed,
    required this.onToggleCollapse,
    required this.destination,
    required this.settingsOpen,
    required this.onDestinationSelected,
    required this.onOpenSettings,
    required this.palette,
  });

  @override
  Widget build(BuildContext context) {
    // 1:1 from Kotlin: tween durationMillis = 180, target 72.dp or 220.dp
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeInOut,
      width: collapsed ? 72.0 : 220.0,
      height: double.infinity,
      clipBehavior: Clip.hardEdge,
      decoration: BoxDecoration(
        color: palette.sidebarSurface,
        border: Border(
          right: BorderSide(
            color: palette.divider,
            width: 1.0,
          ),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.only(
          left: 12.0,
          right: 12.0,
          top: 16.0,
          bottom: 16.0,
        ),
        child: Column(
          children: [
            // Top logo & collapse toggle
            if (collapsed) ...[
              SizedBox(
                height: 35.0,
                child: Center(
                  child: IconButton(
                    padding: EdgeInsets.zero,
                    iconSize: 28.0,
                    splashRadius: 20.0,
                    onPressed: onToggleCollapse,
                    icon: SvgPicture.asset(
                      'assets/icons/bitchord_logo_original.svg',
                      width: 28.0,
                      height: 18.0,
                      colorFilter: ColorFilter.mode(
                        palette.foreground,
                        BlendMode.srcIn,
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16.0),
            ] else ...[
              Padding(
                padding: const EdgeInsets.only(
                  left: 9.0,
                  top: 1.0,
                  bottom: 16.0,
                ),
                child: Row(
                  children: [
                    SvgPicture.asset(
                      'assets/icons/bitchord_logo_original.svg',
                      width: 28.0,
                      height: 18.0,
                      colorFilter: ColorFilter.mode(
                        palette.foreground,
                        BlendMode.srcIn,
                      ),
                    ),
                    const SizedBox(width: 13.0),
                    SvgPicture.asset(
                      'assets/icons/wordmark.svg',
                      width: 84.0,
                      height: 18.0,
                      colorFilter: ColorFilter.mode(
                        palette.foreground,
                        BlendMode.srcIn,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      iconSize: 20.0,
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(
                        minWidth: 28.0,
                        minHeight: 28.0,
                      ),
                      onPressed: onToggleCollapse,
                      icon: Icon(
                        Icons.chevron_left_rounded,
                        color: palette.secondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],

            // Navigation items: Home, New, Search, Library
            DesktopSidebarItem(
              iconAsset: 'assets/icons/nav-home.svg',
              label: 'Home',
              selected: !settingsOpen && destination == DesktopDestination.listenNow,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.listenNow),
            ),
            DesktopSidebarItem(
              iconAsset: 'assets/icons/nav-explore.svg',
              label: 'New',
              selected: !settingsOpen && destination == DesktopDestination.newPage,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.newPage),
            ),
            DesktopSidebarItem(
              iconAsset: 'assets/icons/nav-search.svg',
              label: 'Search',
              selected: !settingsOpen &&
                  (destination == DesktopDestination.search ||
                      destination == DesktopDestination.explore),
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.search),
            ),
            DesktopSidebarItem(
              iconAsset: 'assets/icons/nav-library.svg',
              label: 'Library',
              selected: !settingsOpen && destination == DesktopDestination.libraryPage,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.libraryPage),
            ),

            const SizedBox(height: 6.0),
            Divider(color: palette.divider, height: 1.0),
            const SizedBox(height: 12.0),

            // Secondary: History, Downloads, Local Music
            DesktopSidebarItem(
              iconAsset: 'assets/icons/bit-clock.svg',
              label: 'History',
              selected: !settingsOpen && destination == DesktopDestination.history,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.history),
            ),
            DesktopSidebarItem(
              iconAsset: 'assets/icons/bit-download.svg',
              label: 'Downloads',
              selected: !settingsOpen && destination == DesktopDestination.downloads,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.downloads),
            ),
            DesktopSidebarItem(
              iconAsset: 'assets/icons/bit-library.svg',
              label: 'Local Music',
              selected: !settingsOpen && destination == DesktopDestination.localMusic,
              collapsed: collapsed,
              palette: palette,
              onTap: () => onDestinationSelected(DesktopDestination.localMusic),
            ),

            const Spacer(),
            Divider(color: palette.divider, height: 1.0),
            const SizedBox(height: 8.0),

            // Settings
            DesktopSidebarItem(
              icon: Icons.settings_rounded,
              label: 'Settings',
              selected: settingsOpen,
              collapsed: collapsed,
              palette: palette,
              onTap: onOpenSettings,
            ),
          ],
        ),
      ),
    );
  }
}

/// Translated 1:1 from DesktopApp.kt lines 6015-6084 (DesktopSidebarItem).
class DesktopSidebarItem extends StatefulWidget {
  final String? iconAsset;
  final IconData? icon;
  final String label;
  final bool selected;
  final bool collapsed;
  final DesktopThemePalette palette;
  final VoidCallback onTap;

  const DesktopSidebarItem({
    super.key,
    this.iconAsset,
    this.icon,
    required this.label,
    required this.selected,
    required this.collapsed,
    required this.palette,
    required this.onTap,
  });

  @override
  State<DesktopSidebarItem> createState() => _DesktopSidebarItemState();
}

class _DesktopSidebarItemState extends State<DesktopSidebarItem> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    final palette = widget.palette;
    final selected = widget.selected;

    // Hover background colors:
    // alpha = if (selected) 0.035f else 0.075f
    final hoverColor = selected
        ? Colors.white.withValues(alpha: 0.035)
        : Colors.white.withValues(alpha: 0.075);

    final backgroundColor = selected
        ? palette.navSelectedSurface
        : (_isHovered ? hoverColor : Colors.transparent);

    Widget buildIcon(double size) {
      final tint = selected ? palette.navAccent : palette.secondary;
      if (widget.iconAsset != null) {
        return SvgPicture.asset(
          widget.iconAsset!,
          width: size,
          height: size,
          colorFilter: ColorFilter.mode(tint, BlendMode.srcIn),
        );
      }
      return Icon(widget.icon, size: size, color: tint);
    }

    if (widget.collapsed) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 2.0),
        child: Tooltip(
          message: widget.label,
          child: MouseRegion(
            onEnter: (_) => setState(() => _isHovered = true),
            onExit: (_) => setState(() => _isHovered = false),
            cursor: SystemMouseCursors.click,
            child: GestureDetector(
              onTap: widget.onTap,
              child: Container(
                height: 42.0,
                decoration: BoxDecoration(
                  color: backgroundColor,
                  borderRadius: BorderRadius.circular(7.0),
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    if (selected)
                      Align(
                        alignment: Alignment.centerLeft,
                        child: Container(
                          width: 3.0,
                          height: 18.0,
                          decoration: BoxDecoration(
                            color: palette.navAccent,
                            borderRadius: BorderRadius.circular(1.5),
                          ),
                        ),
                      ),
                    buildIcon(20.0),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }

    // Expanded sidebar item
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2.0),
      child: MouseRegion(
        onEnter: (_) => setState(() => _isHovered = true),
        onExit: (_) => setState(() => _isHovered = false),
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          onTap: widget.onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 9.0, vertical: 7.0),
            decoration: BoxDecoration(
              color: backgroundColor,
              borderRadius: BorderRadius.circular(7.0),
            ),
            child: Row(
              children: [
                if (selected)
                  Container(
                    width: 3.0,
                    height: 18.0,
                    decoration: BoxDecoration(
                      color: palette.navAccent,
                      borderRadius: BorderRadius.circular(1.5),
                    ),
                  )
                else
                  const SizedBox(width: 3.0),
                const SizedBox(width: 6.0),
                buildIcon(18.0),
                const SizedBox(width: 10.0),
                Expanded(
                  child: Text(
                    widget.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontFamily: 'SFProDisplay',
                      fontSize: 14.0,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                      color: selected ? palette.foreground : palette.secondary,
                    ),
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
