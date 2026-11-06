# Windows setup source reliability and legacy recovery

## Why `main.msi` disappears

The original BitChord Windows EXE installers used OpenJDK's `jpackage`
`msiwrapper`, which extracts a randomly identified MSI as
`%TEMP%\\<temporary-folder>\\main.msi` and removes it when setup exits.
Windows Installer can need that exact original MSI later when uninstalling or
upgrading BitChord. A different MSI built from the same source is **not**
interchangeable: its `ProductCode` can differ.

## New releases (after this change)

Windows release jobs create **one** `packageMsi` payload and build a
`BitChord-<version>-windows-x64-setup.exe` around those exact MSI bytes.
Both the MSI asset and the EXE are therefore tied to the same `ProductCode`.

The setup EXE verifies its embedded SHA-256 and persists the MSI to
`%LOCALAPPDATA%\\BitChordUpdater\\PackageCache\\<sha256>\\main.msi` before
launching Windows Installer. The directory is a sibling of, **not nested
inside**, the per-user application directory `%LOCALAPPDATA%\\BitChord`.
Keep this cached MSI as long as the matching BitChord installation is present:
it is an installer source, not a disposable download.

The normal in-app updater still downloads a verified EXE and closes the app
before starting setup. No silent elevation or registry cleanup is performed.

## Wrong Temp folder during an upgrade from an older setup.exe

The new setup EXE bundles the source-repair script as a compiled resource
and runs it before invoking Windows Installer, even when **the updater in the
installed old BitChord does not contain this fix**. The updater also runs the
same helper when it supports the newer handoff.

The helper examines only a bounded set of immediate child folders of
%TEMP%, %LOCALAPPDATA%\Temp and the persistent MSI package cache.
It does **not** guess the correct folder by name or timestamp. It accepts
main.msi only when its UpgradeCode matches BitChord, its ProductCode
is installed for the current Windows account, and the installed native
version matches. It copies the validated file to a persistent directory,
adds that source to the Windows Installer source list, and uses
MsiSourceListForceResolutionEx to clear the obsolete LastUsedSource.
A misleading .tmp directory containing a different MSI is skipped.

If no matching original MSI exists in those locations, the helper
does not modify other MSI registrations. The installer can still show
the normal browse-for-source dialog, and the one-time manual recovery
below may be necessary.

## Recover an existing legacy EXE installation

**Old binaries cannot be patched in place**, but the new setup can
automatically repair a stale Temp path when the matching original MSI
is still available. If Windows still requests a missing `main.msi` from a deleted Temp directory:

1. Obtain the **same original BitChord EXE release** used for the installed
   version, and its official SHA-256 digest (from `SHA256SUMS` if present,
   otherwise the GitHub release asset's `digest` field).
   The separately built MSI asset may have a different `ProductCode`.
2. Verify the EXE SHA-256 against that official digest.
3. Run this repository's
   `desktopApp/packaging/windows/recover-legacy-msi.ps1` as the Windows user
   who originally installed BitChord:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\desktopApp\packaging\windows\recover-legacy-msi.ps1 -OriginalSetupExe "C:\path\to\old-setup.exe" -ExpectedSetupSha256 "<64-character-hash-from-release>"
   ```

4. The script reads the legacy EXE's `msi` RT_RCDATA resource **without
   executing setup**, validates the BitChord `UpgradeCode`, confirms the
   extracted MSI `ProductCode` belongs to an installed product for this
   Windows account, and registers only that original MSI path using the
   documented `MsiSourceListAddSourceEx` / `MsiSourceListSetInfo` APIs.
   It does **not** remove products, fake `ProductCode` entries or delete
   Windows Installer files.
5. Retry installing the new BitChord setup after freeing sufficient disk space
   and exiting the running BitChord process.

Recovery refuses mismatched hashes, unrecognized EXEs, different installed
products or inaccessible MSI source registration. In that situation use
Microsoft's official installer repair tooling rather than manually editing
`C:\Windows\Installer` or substituting the wrong MSI.

## CI expectations

`windows-updater-checks.ps1` validates that the generated EXE extracts
the same MSI bytes as the published MSI artifact. The Windows real-upgrade
workflow installs an **original published EXE**, extracts its exact legacy
MSI, creates both correct and wrong Temp/main.msi folders, then runs the
**new EXE** while an updater log handle remains open. The test fails if
setup.exe does not automatically restore the matching old ProductCode or if
it picks the mismatched MSI.
Manual GUI wizard testing is still recommended before publishing.
