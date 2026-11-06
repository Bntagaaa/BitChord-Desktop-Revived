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
        when (installation.format) {
            UpdateFormat.WINDOWS_SETUP -> {
                Files.createDirectories(downloadDirectory)
                checkSpace(downloadDirectory, requiredBytes(packageBytes, beforeDownload = true))
                checkSpace(DesktopUpdateDiagnostics.directory().parent, requiredBytes(packageBytes, beforeDownload = false))
            }
            UpdateFormat.WINDOWS_PORTABLE -> {
                require(packageBytes > 0 && packageBytes <= 4L * GIB) { "Invalid portable ZIP size." }
                Files.createDirectories(downloadDirectory)
                checkSpace(downloadDirectory, packageBytes + 256L * 1024 * 1024)
                // The source ZIP is copied into an operation folder while the
                // new folder is extracted beside the old portable directory.
                checkSpace(DesktopUpdateDiagnostics.directory().parent, packageBytes + 256L * 1024 * 1024)
                installation.portableRoot?.parent?.let {
                    checkSpace(it, packageBytes * 3 + 512L * 1024 * 1024)
                }
            }
            else -> Unit
        }
    }

    fun checkBeforeSetup(installation: UpdateInstallation, source: Path) {
        when (installation.format) {
            UpdateFormat.WINDOWS_SETUP -> checkSpace(DesktopUpdateDiagnostics.directory().parent,
                requiredBytes(Files.size(source), beforeDownload = false))
            UpdateFormat.WINDOWS_PORTABLE -> {
                val packageBytes = Files.size(source)
                require(packageBytes > 0 && packageBytes <= 4L * GIB) { "Invalid portable ZIP size." }
                checkSpace(DesktopUpdateDiagnostics.directory().parent, packageBytes + 256L * 1024 * 1024)
                installation.portableRoot?.parent?.let {
                    checkSpace(it, packageBytes * 3 + 512L * 1024 * 1024)
                }
            }
            else -> Unit
        }
    }

    private fun checkSpace(path: Path, required: Long) {
        Files.createDirectories(path)
        val available = Files.getFileStore(path).usableSpace
        require(available >= required) {
            "Not enough free disk space for a safe Windows update: need about ${required / GIB} GiB free on ${path.toAbsolutePath().root} (available ${available / (1024 * 1024)} MiB). Free space and retry."
        }
    }
}
