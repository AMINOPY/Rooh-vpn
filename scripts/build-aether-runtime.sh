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

if [ ! -d "${UPSTREAM_DIR}/.git" ]; then
  mkdir -p "$(dirname "${UPSTREAM_DIR}")"
  git clone --filter=blob:none --no-checkout "${UPSTREAM_REPO}" "${UPSTREAM_DIR}"
fi

git -C "${UPSTREAM_DIR}" fetch --depth 1 origin "${UPSTREAM_COMMIT}"
git -C "${UPSTREAM_DIR}" checkout --detach "${UPSTREAM_COMMIT}"
git -C "${UPSTREAM_DIR}" submodule update --init --recursive

# Patch only the upstream bridge source to expose a synchronized running-state
# query. Keep its proven native-pthread implementation and JNI_OnLoad intact.
BRIDGE_SRC="${UPSTREAM_DIR}/scripts/aethertun-jni.c"
grep -q 'Java_studio_cluvex_aether_core_TProxyService_isRunning' "${BRIDGE_SRC}" || cat >> "${BRIDGE_SRC}" <<'EOF'

JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_isRunning(JNIEnv *env, jclass clazz)
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
  bash scripts/build-natives.sh psiphon
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
    elif [ "${library}" = "libpt-lyrebird.so" ]; then
      echo "WARNING: optional Tor pluggable transport missing for ${abi}" >&2
    else
      echo "ERROR: required Aether runtime library missing: ${abi}/${library}" >&2
      exit 1
    fi
  done
  echo "Aether + HEV + Psiphon native runtime staged for ${abi}"
done

echo "Native runtime staged in app/src/main/jniLibs."
