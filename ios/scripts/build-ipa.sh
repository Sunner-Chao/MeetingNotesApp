#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROJECT_PATH="$REPO_ROOT/ios/ZhiWuBen.xcodeproj"
BUILD_ROOT="${IOS_BUILD_DIR:-$REPO_ROOT/build/ios-device/$(date +%Y%m%d-%H%M%S)}"
ARCHIVE_PATH="$BUILD_ROOT/ZhiWuBen.xcarchive"
EXPORT_DIR="$BUILD_ROOT/export"
OUTPUT_DIR="${IOS_OUTPUT_DIR:-$REPO_ROOT}"

TEAM_ID="${APPLE_DEVELOPMENT_TEAM:?Set APPLE_DEVELOPMENT_TEAM to the Apple Developer Team ID}"
BUNDLE_ID="${IOS_BUNDLE_ID:-com.oa.automation.zhiwuben.ios}"
EXPORT_METHOD="${IOS_EXPORT_METHOD:-development}"
DEFAULT_ACCOUNT_ENDPOINT="${IOS_DEFAULT_ACCOUNT_ENDPOINT:-}"
DEFAULT_STT_ENDPOINT="${IOS_DEFAULT_STT_ENDPOINT:-}"

if [[ ! "$TEAM_ID" =~ ^[A-Za-z0-9]+$ ]]; then
  echo "APPLE_DEVELOPMENT_TEAM contains invalid characters" >&2
  exit 2
fi
if [[ ! "$BUNDLE_ID" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "IOS_BUNDLE_ID contains invalid characters" >&2
  exit 2
fi
if [[ ! "$EXPORT_METHOD" =~ ^[A-Za-z0-9-]+$ ]]; then
  echo "IOS_EXPORT_METHOD contains invalid characters" >&2
  exit 2
fi

mkdir -p "$BUILD_ROOT" "$OUTPUT_DIR"

xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme ZhiWuBen \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE_PATH" \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" \
  ZHIWUBEN_DEFAULT_ACCOUNT_ENDPOINT="$DEFAULT_ACCOUNT_ENDPOINT" \
  ZHIWUBEN_DEFAULT_STT_ENDPOINT="$DEFAULT_STT_ENDPOINT" \
  CODE_SIGN_STYLE=Automatic \
  -allowProvisioningUpdates \
  clean archive

EXPORT_OPTIONS="$(mktemp -t zhiwuben-export-options).plist"
trap 'rm -f "$EXPORT_OPTIONS"' EXIT
cat > "$EXPORT_OPTIONS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>$EXPORT_METHOD</string>
  <key>signingStyle</key>
  <string>automatic</string>
  <key>teamID</key>
  <string>$TEAM_ID</string>
  <key>stripSwiftSymbols</key>
  <true/>
</dict>
</plist>
EOF

xcodebuild \
  -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$EXPORT_OPTIONS" \
  -allowProvisioningUpdates

IPA_PATH="$(find "$EXPORT_DIR" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
if [[ -z "$IPA_PATH" ]]; then
  echo "Xcode export completed without producing an IPA" >&2
  exit 1
fi

VERSION="$(/usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleShortVersionString' "$ARCHIVE_PATH/Info.plist")"
DESTINATION="$OUTPUT_DIR/ZhiWuBen-iOS-v$VERSION.ipa"
cp "$IPA_PATH" "$DESTINATION"
shasum -a 256 "$DESTINATION"
echo "IPA: $DESTINATION"
