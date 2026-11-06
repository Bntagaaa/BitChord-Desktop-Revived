// BitChord's Windows MSI source must survive setup exit. Unlike jpackage's
// default msiwrapper, this launcher extracts the *same released MSI* to a
// stable directory outside %LOCALAPPDATA%\BitChord and installs from there.
// Source file stays in place so repair and future major upgrades can find it.
// Requires the .NET Framework already included in supported Windows systems.
using System;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

internal static class BitChordSetup {
    private const string Magic = "BCHMSI01";
    private const int FooterBytes = 8 + 32 + 8;
    private const long MaximumMsiSize = 4L * 1024 * 1024 * 1024;

    [STAThread]
    private static int Main(string[] args) {
        try {
            bool verifyOnly = args.Length == 1 && args[0] == "--verify-payload";
            bool quiet = args.Length == 1 && args[0] == "--quiet";
            if (args.Length != 0 && !verifyOnly && !quiet)
                throw new InvalidOperationException("Unsupported installer option.");
            string cached = CacheEmbeddedMsi();
            if (verifyOnly) {
                Console.WriteLine(cached);
                return 0;
            }

            // /i offers the normal Windows Installer wizard. The old application
            // must be closed by our updater before launching this setup.
            var start = new ProcessStartInfo {
                FileName = Path.Combine(Environment.SystemDirectory, "msiexec.exe"),
                Arguments = "/i \"" + cached + "\"" + (quiet ? " /qn /norestart" : ""),
                UseShellExecute = true
            };
            using (Process installer = Process.Start(start)) {
                if (installer == null) throw new IOException("Windows Installer could not start.");
                installer.WaitForExit();
                return installer.ExitCode;
            }
        } catch (Exception exception) {
            MessageBox.Show("BitChord setup could not continue:\n" + exception.Message +
                "\n\nYour existing installation was not removed by BitChord setup.",
                "BitChord Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string CacheEmbeddedMsi() {
        string self = Process.GetCurrentProcess().MainModule.FileName;
        using (var source = new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.Read)) {
            if (source.Length < FooterBytes) throw new InvalidDataException("Invalid setup payload.");
            source.Seek(-FooterBytes, SeekOrigin.End);
            long payloadSize;
            byte[] expected;
            using (var reader = new BinaryReader(source, Encoding.ASCII, true)) {
                payloadSize = reader.ReadInt64();
                expected = reader.ReadBytes(32);
                if (Encoding.ASCII.GetString(reader.ReadBytes(8)) != Magic ||
                    expected.Length != 32 || payloadSize <= 0 || payloadSize > MaximumMsiSize ||
                    payloadSize >= source.Length - FooterBytes)
                    throw new InvalidDataException("Setup MSI footer is invalid.");
            }
            long offset = source.Length - FooterBytes - payloadSize;
            string fingerprint = BitConverter.ToString(expected).Replace("-", "").ToLowerInvariant();
            string directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BitChordUpdater", "PackageCache", fingerprint);
            Directory.CreateDirectory(directory);
            string target = Path.Combine(directory, "main.msi");
            if (File.Exists(target) && FileMatches(target, payloadSize, expected))
                return target;

            string partial = Path.Combine(directory, Guid.NewGuid().ToString("N") + ".part");
            try {
                source.Seek(offset, SeekOrigin.Begin);
                using (var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                using (var sha = SHA256.Create()) {
                    byte[] buffer = new byte[1024 * 1024];
                    long remaining = payloadSize;
                    while (remaining > 0) {
                        int count = source.Read(buffer, 0, (int)Math.Min(remaining, buffer.Length));
                        if (count <= 0) throw new EndOfStreamException("Truncated setup MSI.");
                        output.Write(buffer, 0, count);
                        sha.TransformBlock(buffer, 0, count, null, 0);
                        remaining -= count;
                    }
                    sha.TransformFinalBlock(new byte[0], 0, 0);
                    if (!EqualHash(sha.Hash, expected))
                        throw new InvalidDataException("Embedded MSI checksum mismatch.");
                    output.Flush(true);
                }
                // A valid pre-existing cache entry is also okay (parallel setup).
                if (File.Exists(target)) {
                    if (!FileMatches(target, payloadSize, expected))
                        throw new InvalidDataException("Unexpected MSI in the package cache.");
                } else {
                    try { File.Move(partial, target); }
                    catch (IOException) {
                        if (!FileMatches(target, payloadSize, expected)) throw;
                    }
                }
                return target;
            } finally {
                if (File.Exists(partial)) File.Delete(partial);
            }
        }
    }

    private static bool FileMatches(string path, long expectedSize, byte[] expectedHash) {
        using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
            if (stream.Length != expectedSize) return false;
            using (var sha = SHA256.Create()) return EqualHash(sha.ComputeHash(stream), expectedHash);
        }
    }

    private static bool EqualHash(byte[] actual, byte[] expected) {
        if (actual == null || actual.Length != expected.Length) return false;
        int diff = 0;
        for (int i = 0; i < actual.Length; i++) diff |= actual[i] ^ expected[i];
        return diff == 0;
    }
}
