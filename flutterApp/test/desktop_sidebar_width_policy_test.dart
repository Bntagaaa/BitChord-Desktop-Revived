import 'package:flutter_test/flutter_test.dart';
import 'package:bitchord_desktop/presentation/sidebar_policy.dart';

void main() {
  test('narrow windows use a rail instead of hiding desktop navigation', () {
    expect(DesktopSidebarWidthPolicy.shouldUseRail(640.0), isTrue);
    expect(DesktopSidebarWidthPolicy.shouldUseRail(979.0), isTrue);
    expect(DesktopSidebarWidthPolicy.shouldUseRail(980.0), isFalse);
    expect(DesktopSidebarWidthPolicy.shouldUseRail(1440.0), isFalse);
  });

  test('automatic collapse does not depend on the saved preference', () {
    expect(DesktopSidebarWidthPolicy.isInlineCollapsed(800.0, false), isTrue);
    expect(DesktopSidebarWidthPolicy.isInlineCollapsed(800.0, true), isTrue);
    expect(DesktopSidebarWidthPolicy.isInlineCollapsed(1200.0, true), isTrue);
    expect(DesktopSidebarWidthPolicy.isInlineCollapsed(1200.0, false), isFalse);
  });

  test('expanded drawer only overlays narrow windows when requested', () {
    expect(DesktopSidebarWidthPolicy.isDrawerVisible(800.0, false), isFalse);
    expect(DesktopSidebarWidthPolicy.isDrawerVisible(800.0, true), isTrue);
    expect(DesktopSidebarWidthPolicy.isDrawerVisible(1200.0, true), isFalse);
  });
}
