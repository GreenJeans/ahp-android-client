#!/usr/bin/env bash
# Archives a Release build of the iOS app and uploads it to App Store Connect (TestFlight).
#
#   tools/ios-testflight.sh            # archive + upload
#   tools/ios-testflight.sh --no-upload  # archive + export an .ipa to build/ios-export only
#
# Upload auth: an App Store Connect API key with the Admin role (App Manager keys cannot use the
# cloud-managed distribution certificate). Put AuthKey_<ID>.p8 in ~/.appstoreconnect/private_keys/, then:
#   ASC_KEY_ID=<ID> ASC_ISSUER_ID=<uuid> tools/ios-testflight.sh
# (ASC_KEY_PATH overrides the key file location.)
#
# Signing is automatic: Xcode uses the Apple ID signed in under Xcode > Settings > Accounts and creates
# the Apple Distribution certificate / App Store profile on first run.
# Bump CURRENT_PROJECT_VERSION in iosApp/Configuration/Signing.xcconfig before every upload.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XCCONFIG="$ROOT/iosApp/Configuration/Signing.xcconfig"
ARCHIVE="$ROOT/build/archive/Hostline.xcarchive"
EXPORT_DIR="$ROOT/build/ios-export"
OPTIONS="$ROOT/iosApp/ExportOptions.plist"

setting() { sed -n "s/^$1 *= *//p" "$XCCONFIG" | tr -d '[:space:]'; }
VERSION="$(setting MARKETING_VERSION)"
BUILD="$(setting CURRENT_PROJECT_VERSION)"
echo "Hostline iOS $VERSION ($BUILD)"

UPLOAD=1
[[ "${1:-}" == "--no-upload" ]] && UPLOAD=0

rm -rf "$ARCHIVE"
xcodebuild -project "$ROOT/iosApp/AhpClient.xcodeproj" -scheme AhpClient -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  -derivedDataPath "$ROOT/build/ios-dd-release" -allowProvisioningUpdates archive

if [[ $UPLOAD -eq 1 ]]; then
  OPTS="$OPTIONS"
else
  OPTS="$(mktemp -t exportopts).plist"
  sed 's#<string>upload</string>#<string>export</string>#' "$OPTIONS" > "$OPTS"
fi

# App Store Connect API key (Users and Access > Integrations > App Store Connect API, role Admin).
# Without it, xcodebuild falls back to the Apple ID signed in under Xcode > Settings > Accounts.
AUTH=()
if [[ -n "${ASC_KEY_ID:-}" ]]; then
  ASC_KEY_PATH="${ASC_KEY_PATH:-$HOME/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8}"
  : "${ASC_ISSUER_ID:?set ASC_ISSUER_ID to the issuer ID from App Store Connect}"
  AUTH=(-authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
fi

rm -rf "$EXPORT_DIR"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$OPTS" -allowProvisioningUpdates ${AUTH[@]+"${AUTH[@]}"}

if [[ $UPLOAD -eq 1 ]]; then
  echo "Uploaded $VERSION ($BUILD). It appears in App Store Connect > TestFlight after processing (~5-15 min)."
else
  echo "Exported to $EXPORT_DIR"
fi
