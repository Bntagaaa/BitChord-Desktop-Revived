package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows EXE wraps an MSI; Windows Installer also stages rollback material.
 * The figures are conservative, not an exact prediction of MSI disk use.
 */
internal object DesktopUpdateDiskSpace {
    private const val GIB = 1024L * 1024 * 1024
    private const val MARGIN = 2L * GIB

    internal fun requiredBytes(packageBytes: Long, beforeDownload: Boolean): Long {
        require(packageBytes > 0 && packageBytes <= 4L * GIB) { "Invalid update package size." }
        // New install + extracted MSI + rollback, plus the download and helper copy.
        return packageBytes * (if (beforeDownload) 5 else 4) + MARGIN
    }

    fun checkBeforeDownload(installation: UpdateInstallation, downloadDirectory: Path, packageBytes: Long) {
        if (installation.format != UpdateFormat.WINDOWS_SETUP) return
        Files.createDirectories(downloadDirectory)
        checkSpace(downloadDirectory, requiredBytes(packageBytes, beforeDownload = true))
        // Downloads and helper staging can be on different volumes.
        checkSpace(DesktopUpdateDiagnostics.directory().parent, requiredBytes(packageBytes, beforeDownload = false))
    }

    fun checkBeforeSetup(installation: UpdateInstallation, source: Path) {
        if (installation.format != UpdateFormat.WINDOWS_SETUP) return
        val size = Files.size(source)
        checkSpace(DesktopUpdateDiagnostics.directory().parent, requiredBytes(size, beforeDownload = false))
    }

    private fun checkSpace(path: Path, required: Long) {
        Files.createDirectories(path)
        val available = Files.getFileStore(path).usableSpace
        require(available >= required) {
            "Not enough free disk space for a safe Windows update: need about ${required / GIB} GiB free on ${path.toAbsolutePath().root} (available ${available / (1024 * 1024)} MiB). Free space and retry."
        }
    }
}
