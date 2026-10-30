#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="${ROOT}/.native/aether-mobile"
UPSTREAM_REPO="https://github.com/QW-AI-Code/Aether.git"
UPSTREAM_COMMIT="b3f7a501cb2e2106b5fc43a6ee15310042c520e6"
OUTPUT_DIR="${ROOT}/app/src/main/jniLibs"
API="${ANDROID_API:-26}"
HEV_REF="${HEV_REF:-2.19.3}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the installed Android NDK}"

NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  candidate="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
  if [ -d "${candidate}" ]; then NDK_TOOLCHAIN="${candidate}"; break; fi
done
[ -n "${NDK_TOOLCHAIN}" ] || { echo "ERROR: NDK LLVM toolchain not found" >&2; exit 1; }

if [ ! -d "${UPSTREAM_DIR}/.git" ]; then
  mkdir -p "$(dirname "${UPSTREAM_DIR}")"
  git clone --filter=blob:none --no-checkout "${UPSTREAM_REPO}" "${UPSTREAM_DIR}"
fi

git -C "${UPSTREAM_DIR}" fetch --depth 1 origin "${UPSTREAM_COMMIT}"
git -C "${UPSTREAM_DIR}" checkout --detach "${UPSTREAM_COMMIT}"
git -C "${UPSTREAM_DIR}" submodule update --init --recursive

# Add the RØH-maintained C JNI extension to the pinned upstream translation
# unit. Keeping the source in this repository makes the ABI addition reviewable.
BRIDGE_SRC="${UPSTREAM_DIR}/scripts/aethertun-jni.c"
RUNNING_JNI="${ROOT}/native/aether/aethertun-running-jni.c.inc"
if ! grep -q 'Java_studio_cluvex_aether_core_TProxyService_isRunning' "${BRIDGE_SRC}"; then
  cat "${RUNNING_JNI}" >> "${BRIDGE_SRC}"
fi

# The pinned upstream owns the complete, maintained Android native build:
# hev TUN bridge, Aether executable, Tor pluggable transports, and Psiphon.
# Build only components used by this app; avoid bundling upstream Xray as RØH
# already builds its own Xray/sing-box runtime.
(
  cd "${UPSTREAM_DIR}"
  export ANDROID_NDK_HOME ANDROID_API="${API}"
  export HEV_REF AETHER_FEATURES="${AETHER_FEATURES:-tor}"
  bash scripts/fetch-natives.sh
  actual_hev_ref="$(git -C .native/hev-socks5-tunnel describe --tags --exact-match 2>/dev/null || true)"
  if [ "${actual_hev_ref}" != "${HEV_REF}" ]; then
    echo "ERROR: HEV checkout is not the required tag ${HEV_REF} (got ${actual_hev_ref:-untagged})." >&2
    exit 1
  fi
  bash scripts/build-natives.sh hev
  bash scripts/build-natives.sh aether
  bash scripts/build-natives.sh pt
  # Psiphon is staged opportunistically; Aether/WARP does not depend on it.
  if ! bash scripts/build-natives.sh psiphon; then
    echo "WARNING: optional Psiphon build failed; Aether/WARP remains buildable." >&2
  fi
)

for abi in arm64-v8a armeabi-v7a x86_64; do
  source_dir="${UPSTREAM_DIR}/app/src/main/jniLibs/${abi}"
  target_dir="${OUTPUT_DIR}/${abi}"
  mkdir -p "${target_dir}"
  for library in \
    libaether.so libaethertun.so libhev-socks5-tunnel.so \
    libpsiphon.so libpt-lyrebird.so libc++_shared.so; do
    if [ -s "${source_dir}/${library}" ]; then
      install -m 755 "${source_dir}/${library}" "${target_dir}/${library}"
    elif [ "${library}" = "libpt-lyrebird.so" ] || [ "${library}" = "libpsiphon.so" ]; then
      echo "WARNING: optional ${library} missing for ${abi}" >&2
    else
      echo "ERROR: required Aether runtime library missing: ${abi}/${library}" >&2
      exit 1
    fi
  done
  symbols="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${target_dir}/libaethertun.so")"
  echo "${symbols}" | grep -qw 'Java_studio_cluvex_aether_core_TProxyService_isRunning' || {
    echo "ERROR: ${abi}/libaethertun.so lacks the RØH running-state JNI symbol." >&2
    exit 1
  }
  [ -x "${target_dir}/libaether.so" ] || { echo "ERROR: ${abi}/libaether.so is not executable" >&2; exit 1; }
  if [ -e "${target_dir}/libpsiphon.so" ] && [ ! -x "${target_dir}/libpsiphon.so" ]; then
    echo "ERROR: ${abi}/libpsiphon.so exists but is not executable" >&2
    exit 1
  fi
  echo "Aether + HEV native runtime staged and JNI-verified for ${abi} (Psiphon optional)"
done

echo "Native runtime staged in app/src/main/jniLibs."
