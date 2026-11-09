/// Keeps a desktop navigation rail present even at widths where the old UI switched to mobile tabs.
/// Translated 1:1 from DesktopSidebarWidthPolicy.kt.
class DesktopSidebarWidthPolicy {
  static const double automaticRailBreakpointDp = 980.0;

  static bool shouldUseRail(double windowWidthDp) =>
      windowWidthDp < automaticRailBreakpointDp;

  static bool isInlineCollapsed(double windowWidthDp, bool userCollapsed) =>
      shouldUseRail(windowWidthDp) || userCollapsed;

  static bool isDrawerVisible(double windowWidthDp, bool drawerRequested) =>
      shouldUseRail(windowWidthDp) && drawerRequested;
}
