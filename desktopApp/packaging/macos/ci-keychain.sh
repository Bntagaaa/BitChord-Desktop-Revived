#!/bin/bash
# Disposable test storage only; no signing identity or Apple account is involved.
set -euo pipefail
keychain="$RUNNER_TEMP/bitchord-ci.keychain-db"
case "${1:-}" in
  setup)
    previous="$(security default-keychain -d user | python3 -c 'import sys; print(sys.stdin.read().strip().strip(chr(34)))')"
    printf 'BITCHORD_PREVIOUS_KEYCHAIN=%s\n' "$previous" >> "$GITHUB_ENV"
    ;;
  reset)
    security delete-keychain "$keychain"
    ;;
  cleanup)
    if [[ -n "${BITCHORD_PREVIOUS_KEYCHAIN:-}" ]]; then
      security list-keychains -d user -s "$BITCHORD_PREVIOUS_KEYCHAIN"
      security default-keychain -d user -s "$BITCHORD_PREVIOUS_KEYCHAIN"
    fi
    if [[ -f "$keychain" ]]; then security delete-keychain "$keychain"; fi
    exit 0
    ;;
  *) echo "Expected setup, reset, or cleanup" >&2; exit 2 ;;
esac
password="$(openssl rand -hex 24)"
echo "::add-mask::$password"
security create-keychain -p "$password" "$keychain"
security set-keychain-settings -lut 21600 "$keychain"
security unlock-keychain -p "$password" "$keychain"
security list-keychains -d user -s "$keychain"
security default-keychain -d user -s "$keychain"
unset password
