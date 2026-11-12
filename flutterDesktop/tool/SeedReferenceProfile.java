// Visual fixture setup ONLY for the disposable GitHub-hosted Windows account.
// Never run this against a listener's installed Kotlin Preferences/profile.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.prefs.Preferences;

class SeedReferenceProfile {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) {
            throw new IllegalStateException("Requires a disposable GitHub-hosted reference test account");
        }
        var path = Path.of(args[0]).toAbsolutePath().normalize();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        var normalized = path.toString().toLowerCase(Locale.ROOT);
        var identity = normalized + "|" + attributes.size() + "|" +
            attributes.creationTime().toMillis() + "|" + attributes.lastModifiedTime().toMillis();
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(identity.getBytes(StandardCharsets.UTF_8)));
        var preferences = Preferences.userRoot().node("com.music.bitchord.desktop");
        preferences.clear();
        preferences.putBoolean("first_run_install_v2_" + digest.substring(0, 40), true);
        preferences.put("desktop_theme_mode", "LIGHT");
        preferences.put("sidebar_collapsed", "false");
        preferences.put("app_font", "DEFAULT");
        preferences.putInt("desktop_ui_scale_percent", 100);
        preferences.flush();
        System.out.println("Prepared fresh reference fixture: Light, 100% UI scale, no accounts or queue");
    }
}
