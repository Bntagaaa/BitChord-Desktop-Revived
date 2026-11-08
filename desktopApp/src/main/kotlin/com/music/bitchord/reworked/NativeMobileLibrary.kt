package com.music.bitchord.reworked

import java.nio.file.Files
import java.security.MessageDigest

/** Loads the packaged Windows/Linux build of the current mobile JNI implementation. */
internal object NativeMobileLibrary {
    @Synchronized fun load(name: String) {
        require(name == "bitchord_loopmix")
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val platform = if (windows) "windows-x86_64" else "linux-x86_64"
        val file = if (windows) "$name.dll" else "lib$name.so"
        val bytes = javaClass.classLoader.getResourceAsStream("native/$platform/$file")?.use { it.readBytes() }
            ?: error("The mobile $name library is missing from this package")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val directory = DesktopStore.cache.resolve("mobile-native").resolve(hash)
        Files.createDirectories(directory)
        val destination = directory.resolve(file)
        if (!Files.isRegularFile(destination) || Files.size(destination) != bytes.size.toLong()) Files.write(destination, bytes)
        System.load(destination.toAbsolutePath().toString())
    }
}
