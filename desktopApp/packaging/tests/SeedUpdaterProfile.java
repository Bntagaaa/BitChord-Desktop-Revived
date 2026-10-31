import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.prefs.Preferences;

/** Test-only profile; never accept a real user home. Runs outside the packaged application. */
class SeedUpdaterProfile {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Path.of(System.getProperty("user.home")).equals(base.resolve("home")) ||
            !Path.of(System.getProperty("java.util.prefs.userRoot")).equals(base.resolve("prefs")))
            throw new IllegalArgumentException("An isolated fixture profile is required");
        var preferences = Preferences.userRoot().node("com.music.bitchord.desktop");
        if (args[1].equals("verify")) {
            if (!preferences.get("updater_fixture_preserve", "").equals("queue-and-settings"))
                throw new AssertionError("Fixture preferences were lost");
            return;
        }
        Path current = Path.of(args[1]).toAbsolutePath().normalize();
        for (int i = 1; i < args.length; i++) {
            Path image = Path.of(args[i]).toAbsolutePath().normalize();
            if (!image.startsWith(base)) throw new IllegalArgumentException("Fixture files must be isolated");
            var attrs = Files.readAttributes(image, BasicFileAttributes.class);
            String identity = current + "|" + attrs.size() + "|" + attrs.creationTime().toMillis() + "|" + attrs.lastModifiedTime().toMillis();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            preferences.putBoolean("first_run_install_v2_" + digest.substring(0, 40), true);
        }
        preferences.put("updater_fixture_preserve", "queue-and-settings");
        preferences.putBoolean("updates_auto", false);
        preferences.flush();
    }
}
