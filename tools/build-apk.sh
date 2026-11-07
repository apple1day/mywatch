#!/usr/bin/env bash
# Build the standalone Java app with the official Android SDK, without Gradle.
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'HELP'
Usage: bash tools/build-apk.sh

Requires Python 3, a Java 17+ runtime containing jdk.compiler, Android SDK
Platform 36, and Android SDK Build-Tools 36.0.0 (or compatible newer tools).

Optional environment variables:
  ANDROID_JAR          Full path to API 36 android.jar.
  ANDROID_BUILD_TOOLS  Directory containing aapt2, zipalign, and lib/d8.jar.
  ANDROID_HOME         Standard Android SDK location.
  ANDROID_SDK_ROOT     SDK fallback if ANDROID_HOME is not set.
  JAVA_HOME            Java runtime location.

The downloaded sibling ../toolchain directory is detected automatically.
Otherwise the usual macOS/Linux Android SDK locations are checked.
The app's development signing key is created once in .dev-signing/.
Keep that directory to produce updates signed with the same key.
Output: dist/OnePlus-Watch-Link-0.1.0.apk
HELP
  exit 0
fi
if [[ $# -ne 0 ]]; then
  printf 'Unknown argument. Run with --help for usage.\n' >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd "$script_dir/.." && pwd -P)"
toolchain_root="$project_dir/../toolchain"
app_version="0.1.0"
app_version_code="1"
min_sdk="26"
target_sdk="35"
manifest_path="$project_dir/app/src/main/AndroidManifest.xml"
source_dir="$project_dir/app/src/main/java"
resource_dir="$project_dir/app/src/main/res"
assets_dir="$project_dir/app/src/main/assets"

die() { printf 'Build error: %s\n' "$*" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || die 'Python 3 is required.'
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin="$(command -v java || true)"
fi
[[ -n "$java_bin" ]] || die 'Install Java 17+ or set JAVA_HOME.'
"$java_bin" com.sun.tools.javac.Main -version >/dev/null 2>&1 || \
  die 'The selected Java runtime does not contain the Java compiler; use a full JDK.'

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/lib/android-sdk; do
    if [[ -d "$candidate" ]]; then sdk_root="$candidate"; break; fi
  done
fi

android_jar="${ANDROID_JAR:-}"
if [[ -z "$android_jar" && -f "$toolchain_root/platform-36/android-36/android.jar" ]]; then
  android_jar="$toolchain_root/platform-36/android-36/android.jar"
fi
if [[ -z "$android_jar" && -n "$sdk_root" ]]; then
  android_jar="$sdk_root/platforms/android-36/android.jar"
fi
[[ -n "$android_jar" && -f "$android_jar" ]] || \
  die 'API 36 android.jar was not found. Set ANDROID_JAR or install platforms;android-36.'

build_tools="${ANDROID_BUILD_TOOLS:-}"
if [[ -z "$build_tools" && "$(uname -s)" == "Linux" && -d "$toolchain_root/build-tools-36/android-16" ]]; then
  build_tools="$toolchain_root/build-tools-36/android-16"
fi
if [[ -z "$build_tools" && -n "$sdk_root" && -d "$sdk_root/build-tools/36.0.0" ]]; then
  build_tools="$sdk_root/build-tools/36.0.0"
fi
if [[ -z "$build_tools" && -n "$sdk_root" ]]; then
  build_tools="$(python3 - "$sdk_root/build-tools" <<'PY'
from pathlib import Path
import re, sys
base = Path(sys.argv[1])
versions = []
if base.is_dir():
    for path in base.iterdir():
        if path.is_dir() and re.fullmatch(r'\d+\.\d+\.\d+', path.name):
            version = tuple(map(int, path.name.split('.')))
            if version >= (36, 0, 0):
                versions.append((version, path))
if versions:
    print(max(versions)[1])
PY
)"
fi
[[ -n "$build_tools" ]] || die 'Set ANDROID_BUILD_TOOLS or install build-tools;36.0.0.'
for required in aapt2 zipalign lib/d8.jar lib/apksigner.jar core-lambda-stubs.jar; do
  [[ -f "$build_tools/$required" ]] || die "Missing SDK tool: $build_tools/$required"
done
[[ -x "$build_tools/aapt2" && -x "$build_tools/zipalign" ]] || \
  die 'The selected aapt2 and zipalign must be executable tools for this host.'
[[ -f "$manifest_path" && -d "$source_dir" ]] || die 'App manifest or Java source directory is missing.'

mkdir -p "$project_dir/build" "$project_dir/dist"
work_dir="$(mktemp -d "$project_dir/build/manual.XXXXXX")"
finish() {
  local result=$?
  if [[ "$result" -eq 0 ]]; then
    rm -rf "$work_dir"
  else
    printf 'Intermediate files retained at: %s\n' "$work_dir" >&2
  fi
}
trap finish EXIT
mkdir -p "$work_dir/generated" "$work_dir/classes" "$work_dir/dex"

printf 'Compiling Android resources (min SDK %s, target SDK %s)...\n' "$min_sdk" "$target_sdk"
link_args=(link -I "$android_jar" --manifest "$manifest_path"
  --min-sdk-version "$min_sdk" --target-sdk-version "$target_sdk"
  --version-code "$app_version_code" --version-name "$app_version"
  --java "$work_dir/generated" -o "$work_dir/resources.apk")
if [[ -d "$resource_dir" ]] && python3 - "$resource_dir" <<'PY'
from pathlib import Path
import sys
sys.exit(0 if any(p.is_file() for p in Path(sys.argv[1]).rglob('*')) else 1)
PY
then
  "$build_tools/aapt2" compile --dir "$resource_dir" -o "$work_dir/resources.zip"
  link_args+=("$work_dir/resources.zip")
fi
if [[ -d "$assets_dir" ]]; then link_args+=(-A "$assets_dir"); fi
"$build_tools/aapt2" "${link_args[@]}"

python3 - "$source_dir" "$work_dir/generated" "$work_dir/sources.args" <<'PY'
from pathlib import Path
import sys
sources = sorted(Path(sys.argv[1]).rglob('*.java')) + sorted(Path(sys.argv[2]).rglob('*.java'))
if not sources:
    raise SystemExit('No Java sources found')
with open(sys.argv[3], 'w', encoding='utf-8') as handle:
    for source in sources:
        name = str(source.resolve())
        if '\n' in name or '\r' in name:
            raise SystemExit('Source paths containing newline characters are unsupported')
        handle.write('"' + name.replace('\\', '\\\\').replace('"', '\\"') + '"\n')
PY
printf 'Compiling Java 8 bytecode...\n'
# Android supplies the bootstrap API surface. Using --release would instead
# require the host JDK's ct.sym (not present in compact runtime images).
"$java_bin" com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$android_jar:$build_tools/core-lambda-stubs.jar" \
  -d "$work_dir/classes" "@$work_dir/sources.args"
"$java_bin" --module jdk.jartool/sun.tools.jar.Main \
  --create --file "$work_dir/classes.jar" -C "$work_dir/classes" .
"$java_bin" -cp "$build_tools/lib/d8.jar" com.android.tools.r8.D8 \
  --debug --min-api "$min_sdk" --lib "$android_jar" \
  --output "$work_dir/dex" "$work_dir/classes.jar"

python3 - "$work_dir/resources.apk" "$work_dir/dex" <<'PY'
from pathlib import Path
import sys, zipfile
dex_files = sorted(Path(sys.argv[2]).glob('*.dex'))
if not dex_files:
    raise SystemExit('D8 did not produce any DEX files')
with zipfile.ZipFile(sys.argv[1], 'a') as archive:
    for path in dex_files:
        archive.write(path, path.name, compress_type=zipfile.ZIP_DEFLATED)
PY
"$build_tools/zipalign" -f -P 16 4 "$work_dir/resources.apk" "$work_dir/aligned.apk"

signing_dir="$project_dir/.dev-signing"
signing_key="$signing_dir/development.p12"
if [[ ! -f "$signing_key" ]]; then
  # This development key is separate from every vendor or user production key.
  # Persist the directory so subsequent builds can update this app in place.
  (
    umask 077
    mkdir -p "$signing_dir"
    keytool_bin="$(command -v keytool || true)"
    if [[ -n "$keytool_bin" ]]; then
      keytool_command=("$keytool_bin")
    else
      keytool_command=("$java_bin" --module java.base/sun.security.tools.keytool.Main)
    fi
    "${keytool_command[@]}" -genkeypair -noprompt -storetype PKCS12 \
      -keystore "$signing_key" -alias development -storepass android -keypass android \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname 'CN=OnePlus Watch Link Development, OU=Personal Development, O=Local Development'
  )
fi

printf 'Signing with the project development key and verifying the APK...\n'
"$java_bin" -jar "$build_tools/lib/apksigner.jar" sign \
  --ks "$signing_key" --ks-key-alias development --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version "$min_sdk" --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out "$work_dir/signed.apk" "$work_dir/aligned.apk"
"$java_bin" -jar "$build_tools/lib/apksigner.jar" verify \
  --min-sdk-version "$min_sdk" --verbose --print-certs "$work_dir/signed.apk"
"$build_tools/zipalign" -c -P 16 4 "$work_dir/signed.apk"

apk_path="$project_dir/dist/OnePlus-Watch-Link-$app_version.apk"
chmod 644 "$work_dir/signed.apk"
mv -f "$work_dir/signed.apk" "$apk_path"
python3 - "$apk_path" <<'PY'
from pathlib import Path
import hashlib, sys
path = Path(sys.argv[1])
digest = hashlib.sha256(path.read_bytes()).hexdigest()
path.with_name(path.name + '.sha256').write_text(digest + '  ' + path.name + '\n', encoding='ascii')
print('APK:', path)
print('SHA-256:', digest)
print('Build and signing checks passed. Bluetooth behavior still requires a physical phone and watch.')
PY
