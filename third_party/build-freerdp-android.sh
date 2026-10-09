#!/usr/bin/env bash
# Reproducible Android (arm64-v8a, API 26) cross-build of FreeRDP 3.x + JNI wrapper.
# github.com is unreachable here, so all deps are vendored from Gitee mirrors and
# the Android External*.cmake are patched to skip downloading.
set -euo pipefail

WS=/home/typheye/Code/otodone/aosp-app
FREERDP="$WS/third_party/FreeRDP"
NDK=/home/typheye/Android/Sdk/ndk/29.0.13113456
CMAKE=/home/typheye/Android/Sdk/cmake/3.31.6/bin/cmake
BUILD="$WS/third_party/freerdp-android-build"
OUT="$WS/third_party/freerdp-android-out"
SRC="$FREERDP/client/Android/Studio/freeRDPCore/src/main/cpp"
PATCH="$OUT/patches/0001-android-vendored-external-deps.patch"
PATCH_OPENH264="$OUT/patches/0002-android-vendored-openh264.patch"

# 1) Vendor dependency sources from Gitee (depth-1 tag clones).
clone_dep () { # <name> <tag> <gitee-repo>
  local dir="$FREERDP/external/$1" tag="$2" repo="$3"
  if [ ! -e "$dir/README.md" ] && [ ! -e "$dir/Configure" ] && [ ! -d "$dir" ]; then
    git -c http.https://gitee.com.proxy= clone --depth 1 --branch "$tag" "$repo" "$dir"
  fi
}
[ -d "$FREERDP/external/openssl" ]   || git -c http.https://gitee.com.proxy= clone --depth 1 --branch openssl-4.0.1   https://gitee.com/mirrors/openssl   "$FREERDP/external/openssl"
[ -d "$FREERDP/external/uriparser" ] || git -c http.https://gitee.com.proxy= clone --depth 1 --branch uriparser-1.0.2 https://gitee.com/mirrors/uriparser "$FREERDP/external/uriparser"
[ -d "$FREERDP/external/cjson" ]     || git -c http.https://gitee.com.proxy= clone --depth 1 --branch v1.7.19       https://gitee.com/mirrors/cJSON     "$FREERDP/external/cjson"
# OpenH264 H.264 codec (required for RDPGFX AVC420 / AVC444).
[ -d "$FREERDP/external/openh264" ] || git -c http.https://gitee.com.proxy= clone --depth 1 --branch v2.6.0        https://gitee.com/mirrors/openh264  "$FREERDP/external/openh264"

# 2) Patch External*.cmake to use the vendored sources (skip download/update).
for p in "$PATCH" "$PATCH_OPENH264"; do
  if git -C "$FREERDP" apply --check "$p" 2>/dev/null; then
    git -C "$FREERDP" apply "$p"
  fi
done

# 3) Configure (top-level JNI project; it drives openssl + uriparser + cjson + freerdp via ExternalProject).
rm -rf "$BUILD"
mkdir -p "$BUILD" "$OUT"
"$CMAKE" -G Ninja -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$OUT" \
  -DFREERDP_ANDROID_DEPS_ROOT="$OUT" \
  -DWITH_OPENSSL=ON -DWITH_CJSON=ON \
  -DWITH_FFMPEG=OFF -DWITH_OPENH264=ON -DWITH_OPUS=OFF \
  -DWITH_WEBP=OFF -DWITH_JPEG=OFF -DWITH_PNG=OFF

# 4) Build everything (openssl -> uriparser/cjson -> freerdp3/winpr3 -> libfreerdp-android.so)
"$CMAKE" --build "$BUILD" -j "$(nproc)"

# 5) The JNI wrapper is not installed by CMake; copy it next to the other .so files.
cp -f "$BUILD/libfreerdp-android.so" "$OUT/arm64-v8a/libfreerdp-android.so"

echo "DONE. Outputs in $OUT/arm64-v8a"
ls -lh "$OUT/arm64-v8a/"*.so

# 6) Mirror the native libraries into the Android app (overwrites same names,
#    adds libopenh264.so which is a new DT_NEEDED of libfreerdp3.so).
JNILIBS="$WS/app/src/main/jniLibs/arm64-v8a"
if [ -d "$JNILIBS" ]; then
  cp -f "$OUT/arm64-v8a/"*.so "$JNILIBS/"
  echo "Synced .so files to $JNILIBS"
fi
