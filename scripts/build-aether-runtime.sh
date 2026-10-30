#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="${ROOT}/.native/aether-mobile"
JNI_DIR="${ROOT}/app/src/main/jniLibs"
AETHER_REPO="https://github.com/QW-AI-Code/Aether.git"
AETHER_COMMIT="b3f7a501cb2e2106b5fc43a6ee15310042c520e6"
HEV_REF="${HEV_REF:-2.19.3}"
API="${ANDROID_API:-26}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the installed Android NDK}"

if [ ! -d "${UPSTREAM_DIR}/.git" ]; then
  mkdir -p "$(dirname "${UPSTREAM_DIR}")"
  git clone --filter=blob:none --recurse-submodules "${AETHER_REPO}" "${UPSTREAM_DIR}"
fi
git -C "${UPSTREAM_DIR}" fetch --depth 1 origin "${AETHER_COMMIT}"
git -C "${UPSTREAM_DIR}" checkout --detach "${AETHER_COMMIT}"
git -C "${UPSTREAM_DIR}" submodule update --init --recursive

# Use the upstream's own source-fetch and native-build recipes. The Aether
# source is pinned by commit; HEV is pinned to its release tag.
(
  cd "${UPSTREAM_DIR}"
  HEV_REF="${HEV_REF}" bash scripts/fetch-natives.sh
  ANDROID_API="${API}" bash scripts/build-natives.sh aether
  ANDROID_API="${API}" bash scripts/build-natives.sh hev
)

NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  candidate="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
  if [ -d "${candidate}" ]; then NDK_TOOLCHAIN="${candidate}"; break; fi
done
[ -n "${NDK_TOOLCHAIN}" ] || { echo "NDK LLVM toolchain not found" >&2; exit 1; }

clang_for_abi() {
  case "$1" in
    arm64-v8a) echo "${NDK_TOOLCHAIN}/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "${NDK_TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" ;;
    x86_64) echo "${NDK_TOOLCHAIN}/x86_64-linux-android${API}-clang" ;;
    *) return 1 ;;
  esac
}

SOURCE="${UPSTREAM_DIR}/scripts/aethertun-jni.c"
[ -s "${SOURCE}" ] || { echo "Upstream JNI bridge source is missing" >&2; exit 1; }

for abi in arm64-v8a armeabi-v7a x86_64; do
  upstream_libs="${UPSTREAM_DIR}/app/src/main/jniLibs/${abi}"
  out="${JNI_DIR}/${abi}"
  [ -d "${upstream_libs}" ] || { echo "Native outputs missing for ${abi}" >&2; exit 1; }
  mkdir -p "${out}"

  # Keep only the Aether executable and its runtime dependencies plus HEV.
  # The original upstream JNI bridge is replaced with a bridge whose JNI ABI
  # belongs to RØH; optional Tor/Psiphon/Xray executables are not bundled here.
  find "${upstream_libs}" -maxdepth 1 -type f -name '*.so' \
    ! -name 'libaethertun.so' \
    ! -name 'libpsiphon.so' \
    ! -name 'libpt-lyrebird.so' \
    ! -name 'libxray.so' \
    -exec cp -f {} "${out}/" \;

  clang="$(clang_for_abi "${abi}")"
  [ -x "${clang}" ] || { echo "NDK clang missing for ${abi}" >&2; exit 1; }
  bridge_source="$(mktemp)"
  sed \
    -e 's/Java_studio_cluvex_aether_core_TProxyService_TProxyStartService/Java_com_aminopy_roohvpn_core_AetherHevBridge_start/g' \
    -e 's/Java_studio_cluvex_aether_core_TProxyService_TProxyStopService/Java_com_aminopy_roohvpn_core_AetherHevBridge_stop/g' \
    -e 's/Java_studio_cluvex_aether_core_TProxyService_TProxyGetStats/Java_com_aminopy_roohvpn_core_AetherHevBridge_getStats/g' \
    "${SOURCE}" > "${bridge_source}"
  cat >> "${bridge_source}" <<'EOF'

JNIEXPORT jboolean JNICALL
Java_com_aminopy_roohvpn_core_AetherHevBridge_isRunning(JNIEnv *env, jclass clazz)
{
    bool value;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&lock);
    value = running;
    pthread_mutex_unlock(&lock);
    return value ? JNI_TRUE : JNI_FALSE;
}
EOF

  "${clang}" -O2 -fPIC -shared -Wall -Werror \
    -Wl,-soname,libroohvpnhev.so -Wl,--no-undefined \
    -o "${out}/libroohvpnhev.so" "${bridge_source}" \
    -L"${out}" -lhev-socks5-tunnel -llog
  rm -f "${bridge_source}"

  symbols="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${out}/libroohvpnhev.so")"
  for symbol in JNI_OnLoad \
    Java_com_aminopy_roohvpn_core_AetherHevBridge_start \
    Java_com_aminopy_roohvpn_core_AetherHevBridge_stop \
    Java_com_aminopy_roohvpn_core_AetherHevBridge_isRunning; do
    echo "${symbols}" | grep -qw "${symbol}" || {
      echo "${abi}: JNI bridge is missing ${symbol}" >&2
      exit 1
    }
  done

  for library in libaether.so libhev-socks5-tunnel.so libroohvpnhev.so; do
    [ -s "${out}/${library}" ] || {
      echo "${abi}: required Aether runtime library missing: ${library}" >&2
      exit 1
    }
  done
  echo "Aether runtime verified for ${abi}"
done

echo "Aether native runtime installed in app/src/main/jniLibs."
