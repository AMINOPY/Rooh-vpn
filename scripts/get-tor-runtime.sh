#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${TOR_CACHE:-$ROOT/build/tor-runtime}"
OUTPUT="${ROOT}/app/src/main/jniLibs"
REPO="CluvexStudio/ZedSecure-Cores"
TAG="tor-r4"
BASE="https://github.com/${REPO}/releases/download/${TAG}"

mkdir -p "$CACHE"
fetch_verified() {
  local asset="$1" expected="$2" file="$CACHE/$1"
  if [ ! -s "$file" ] || [ "$(sha256sum "$file" | awk '{print $1}')" != "$expected" ]; then
    curl -fsSL --retry 3 "$BASE/$asset" -o "$file"
  fi
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [ "$actual" = "$expected" ] || {
    echo "ERROR: checksum mismatch for $asset (expected $expected, got $actual)" >&2
    rm -f "$file"
    exit 1
  }
  printf '%s\n' "$file"
}

# SHA-256 values are pinned to the immutable tor-r4 GitHub release assets.
declare -A CHECKSUMS=(
  ["arm64-v8a"]="963acb8e1a845d541954fef1aaa2952ba7e76e38f64072c82fe4215926c2e04f"
  ["armeabi-v7a"]="c30aa7c93cf4dd204bc4f3e731545d9f80750e9d2364d5cfe728ff5f157f3241"
  ["x86_64"]="631e337143561d9b879c62d62020b5e6a7c4fe3442178021884feb97a6222c35"
)

for abi in arm64-v8a armeabi-v7a x86_64; do
  archive="$(fetch_verified "tor-android-$abi.zip" "${CHECKSUMS[$abi]}")"
  stage="$CACHE/stage-$abi"
  rm -rf "$stage"
  mkdir -p "$stage" "$OUTPUT/$abi"
  unzip -q "$archive" -d "$stage"
  for library in libtor.so libobfs4proxy.so libconjure.so libsnowflake.so; do
    [ -s "$stage/$library" ] || {
      echo "ERROR: $abi archive is missing $library" >&2
      exit 1
    }
    install -m 755 "$stage/$library" "$OUTPUT/$abi/$library"
  done
  echo "Verified Tor + obfs4 + Conjure + Snowflake for $abi"
done

# Pin the GeoIP databases to the source commit used by the Tor settings reference.
# Git blob IDs verify the exact file contents without checking large databases in.
TOR_DATA_COMMIT="41796b68fc171a351fa668d7991499cca395c0d0"
declare -A GEOIP_BLOBS=(
  ["geoip"]="5529b3efd38bd70215a4d7eeeb00fe917c648bd1"
  ["geoip6"]="5670454888220ce84302ff8dafc9c30720da679e"
)
mkdir -p "$ROOT/app/src/main/assets/tor"
for database in geoip geoip6; do
  raw="$CACHE/$database"
  if [ ! -s "$raw" ] || [ "$(git hash-object "$raw")" != "${GEOIP_BLOBS[$database]}" ]; then
    curl -fsSL --retry 3 \
      "https://raw.githubusercontent.com/AMINOPY/ZX-VPN-/${TOR_DATA_COMMIT}/app/src/main/assets/tor/$database" \
      -o "$raw"
  fi
  actual="$(git hash-object "$raw")"
  [ "$actual" = "${GEOIP_BLOBS[$database]}" ] || {
    echo "ERROR: pinned Tor $database database hash mismatch" >&2
    rm -f "$raw"
    exit 1
  }
  # Store verified raw data; Android asset packaging may transparently decompress .gz files.
  install -m 644 "$raw" "$ROOT/app/src/main/assets/tor/$database"
done
