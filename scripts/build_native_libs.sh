#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JNILIBS_DIR="$PROJECT_DIR/terminal_core/src/main/jniLibs"
NDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}/ndk/26.1.10909125"
CMAKE_TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
BUILD_DIR="$PROJECT_DIR/build/native_libs"
OUTPUT_DIR="$BUILD_DIR/output"

JOBS=$(nproc 2>/dev/null || echo 4)
API_LEVEL=26

ABIS=(
    "arm64-v8a:aarch64-linux-android"
    "armeabi-v7a:armv7a-linux-androideabi"
    "x86_64:x86_64-linux-android"
    "x86:i686-linux-android"
)

log() { echo "[BUILD] $*"; }

check_prereqs() {
    if [ ! -d "$NDK_DIR" ]; then
        echo "Error: NDK not found at $NDK_DIR"
        echo "Set ANDROID_HOME or adjust NDK_DIR in the script."
        exit 1
    fi
    if [ ! -f "$CMAKE_TOOLCHAIN" ]; then
        echo "Error: CMake toolchain not found at $CMAKE_TOOLCHAIN"
        exit 1
    fi
    command -v cmake >/dev/null 2>&1 || { echo "cmake required"; exit 1; }
    command -v meson >/dev/null 2>&1 || { echo "Installing meson ninja..."; pip install meson ninja 2>/dev/null || true; }
    command -v tar >/dev/null 2>&1 || { echo "tar required"; exit 1; }
    command -v curl >/dev/null 2>&1 || { echo "curl required"; exit 1; }
    command -v git >/dev/null 2>&1 || { echo "git required"; exit 1; }
    command -v make >/dev/null 2>&1 || { echo "make required"; exit 1; }
    mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"
}

# ──────────────────────────────────────────────
# libtalloc — hierarchical memory allocator
# ──────────────────────────────────────────────
build_talloc() {
    local src_dir="$BUILD_DIR/talloc"
    if [ ! -d "$src_dir" ]; then
        log "Cloning talloc..."
        git clone --depth 1 https://git.samba.org/talloc.git "$src_dir"
    fi

    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local host="${abi_entry#*:}"
        local install_dir="$OUTPUT_DIR/talloc/$abi"

        log "Building talloc for $abi..."
        mkdir -p "$install_dir"

        pushd "$src_dir" >/dev/null

        # talloc uses waf, which doesn't support Android cross-compile easily.
        # Build using standalone toolchain + manual compilation.
        local toolchain_dir="$BUILD_DIR/toolchains/$abi"
        if [ ! -d "$toolchain_dir" ]; then
            "$NDK_DIR/build/tools/make_standalone_toolchain.py" \
                --arch "$(echo "$abi" | sed 's/arm64-v8a/arm64/;s/armeabi-v7a/arm/;s/x86_64/x86_64/;s/x86/x86/')" \
                --api "$API_LEVEL" \
                --install-dir "$toolchain_dir"
        fi

        export CC="$toolchain_dir/bin/${host}${API_LEVEL}-clang"
        export AR="$toolchain_dir/bin/llvm-ar"
        export RANLIB="$toolchain_dir/bin/llvm-ranlib"
        export CFLAGS="-Os -fPIC"
        export LDFLAGS="-Wl,--gc-sections"

        ./configure --prefix="$install_dir" --host="$host" --disable-python --enable-talloc-compat1
        make -j"$JOBS"
        make install

        # Copy result
        cp "$install_dir/lib/libtalloc.so" "$install_dir/libtalloc.so" 2>/dev/null || true
        popd >/dev/null
    done
    log "talloc built for all ABIs"
}

# ──────────────────────────────────────────────
# libarchive / bsdtar — native tar extraction
# ──────────────────────────────────────────────
build_bsdtar() {
    local src_dir="$BUILD_DIR/libarchive"
    if [ ! -d "$src_dir" ]; then
        log "Cloning libarchive..."
        git clone --depth 1 https://github.com/libarchive/libarchive.git "$src_dir"
    fi

    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local install_dir="$OUTPUT_DIR/bsdtar/$abi"

        log "Building bsdtar for $abi..."
        mkdir -p "$install_dir"

        local build_abi="$BUILD_DIR/libarchive/build_$abi"
        mkdir -p "$build_abi"
        pushd "$build_abi" >/dev/null

        cmake "$src_dir" \
            -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN" \
            -DANDROID_ABI="$abi" \
            -DANDROID_PLATFORM="android-$API_LEVEL" \
            -DENABLE_TAR=ON \
            -DENABLE_CPIO=OFF \
            -DENABLE_CAT=OFF \
            -DENABLE_TEST=OFF \
            -DENABLE_EXPAT=OFF \
            -DENABLE_LZ4=OFF \
            -DENABLE_ZSTD=OFF \
            -DENABLE_ICONV=OFF \
            -DENABLE_LIBB2=OFF \
            -DENABLE_LZMA=ON \
            -DENABLE_ZLIB=ON \
            -DENABLE_OPENSSL=OFF \
            -DENABLE_NETTLE=OFF \
            -DCMAKE_INSTALL_PREFIX="$install_dir" \
            -DCMAKE_BUILD_TYPE=Release

        cmake --build . --target bsdtar -- -j"$JOBS"
        cmake --install .

        popd >/dev/null
    done
    log "bsdtar built for all ABIs"
}

# ──────────────────────────────────────────────
# PRoot — user-space chroot with seccomp disabled
# ──────────────────────────────────────────────
build_proot() {
    local src_dir="$BUILD_DIR/proot"
    if [ ! -d "$src_dir" ]; then
        log "Cloning PRoot..."
        git clone --depth 1 https://github.com/proot-me/proot.git "$src_dir"
        # Also fetch talloc submodule (PRoot uses talloc)
        pushd "$src_dir" >/dev/null
        git submodule update --init 2>/dev/null || true
        popd >/dev/null
    fi

    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local host="${abi_entry#*:}"
        local install_dir="$OUTPUT_DIR/proot/$abi"

        log "Building PRoot for $abi..."
        mkdir -p "$install_dir"

        local build_abi="$BUILD_DIR/proot/build_$abi"
        mkdir -p "$build_abi"
        pushd "$build_abi" >/dev/null

        # PRoot uses a custom build system (GNU Make based).
        # We configure via environment variables for cross-compilation.
        local toolchain_dir="$BUILD_DIR/toolchains/$abi"
        if [ ! -d "$toolchain_dir" ]; then
            "$NDK_DIR/build/tools/make_standalone_toolchain.py" \
                --arch "$(echo "$abi" | sed 's/arm64-v8a/arm64/;s/armeabi-v7a/arm/;s/x86_64/x86_64/;s/x86/x86/')" \
                --api "$API_LEVEL" \
                --install-dir "$toolchain_dir"
        fi

        export CC="$toolchain_dir/bin/${host}${API_LEVEL}-clang"
        export LD="$toolchain_dir/bin/${host}${API_LEVEL}-clang"
        export AR="$toolchain_dir/bin/llvm-ar"
        export CFLAGS="-Os -fPIE -DPROOT_NO_SECCOMP=1 -DNO_TALLOC"
        export LDFLAGS="-fPIE -pie -static"
        export CPPFLAGS="-I$BUILD_DIR/talloc/$abi/include"

        make -C "$src_dir" -j"$JOBS" \
            CC="$CC" \
            LD="$LD" \
            AR="$AR" \
            CFLAGS="$CFLAGS" \
            LDFLAGS="$LDFLAGS" \
            CPPFLAGS="$CPPFLAGS" \
            DESTDIR="$install_dir" \
            install

        # The result is typically called 'proot' or 'proot-xed'.
        # Rename to libproot.so (Android convention for jniLibs executables).
        if [ -f "$install_dir/bin/proot" ]; then
            cp "$install_dir/bin/proot" "$install_dir/libproot.so"
        elif [ -f "$src_dir/proot" ]; then
            cp "$src_dir/proot" "$install_dir/libproot.so"
        fi

        # Build proot-xed variant (without seccomp compiled out completely)
        make -C "$src_dir" clean 2>/dev/null || true
        export CFLAGS="-Os -fPIE -DNO_TALLOC"
        export LDFLAGS="-fPIE -pie -static"
        make -C "$src_dir" -j"$JOBS" \
            CC="$CC" \
            LD="$LD" \
            AR="$AR" \
            CFLAGS="$CFLAGS" \
            LDFLAGS="$LDFLAGS" \
            CPPFLAGS="$CPPFLAGS" \
            proot-xed

        if [ -f "$src_dir/proot-xed" ]; then
            cp "$src_dir/proot-xed" "$install_dir/libproot-xed.so"
        fi

        popd >/dev/null
    done
    log "PRoot built for all ABIs"
}

# ──────────────────────────────────────────────
# libproot32 — 32-bit PRoot for running 32-bit
# binaries inside the 64-bit container
# ──────────────────────────────────────────────
build_proot32() {
    # 32-bit PRoot is only needed for armeabi-v7a
    local src_dir="$BUILD_DIR/proot"
    if [ ! -d "$src_dir" ]; then
        build_proot  # will clone if needed
    fi

    local install_dir="$OUTPUT_DIR/proot/armeabi-v7a"
    log "Building 32-bit PRoot (libproot32.so)..."
    mkdir -p "$install_dir"

    local toolchain_dir="$BUILD_DIR/toolchains/armeabi-v7a"
    if [ ! -d "$toolchain_dir" ]; then
        "$NDK_DIR/build/tools/make_standalone_toolchain.py" \
            --arch arm \
            --api "$API_LEVEL" \
            --install-dir "$toolchain_dir"
    fi

    export CC="$toolchain_dir/bin/armv7a-linux-androideabi${API_LEVEL}-clang"
    export AR="$toolchain_dir/bin/llvm-ar"
    export CFLAGS="-Os -fPIE -DPROOT_NO_SECCOMP=1 -DNO_TALLOC"
    export LDFLAGS="-fPIE -pie -static"

    make -C "$src_dir" -j"$JOBS" clean 2>/dev/null || true
    make -C "$src_dir" -j"$JOBS" \
        CC="$CC" \
        AR="$AR" \
        CFLAGS="$CFLAGS" \
        LDFLAGS="$LDFLAGS" \
        proot

    if [ -f "$src_dir/proot" ]; then
        cp "$src_dir/proot" "$install_dir/libproot32.so"
        log "libproot32.so created"
    fi
}

# ──────────────────────────────────────────────
# Deploy: copy outputs to jniLibs
# ──────────────────────────────────────────────
deploy() {
    log "Deploying native libs to jniLibs..."
    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local target_dir="$JNILIBS_DIR/$abi"
        mkdir -p "$target_dir"

        # libproot.so
        if [ -f "$OUTPUT_DIR/proot/$abi/libproot.so" ]; then
            cp "$OUTPUT_DIR/proot/$abi/libproot.so" "$target_dir/libproot.so"
            log "  -> $abi/libproot.so"
        fi

        # libproot-xed.so
        if [ -f "$OUTPUT_DIR/proot/$abi/libproot-xed.so" ]; then
            cp "$OUTPUT_DIR/proot/$abi/libproot-xed.so" "$target_dir/libproot-xed.so"
            log "  -> $abi/libproot-xed.so"
        fi

        # libtalloc.so
        if [ -f "$OUTPUT_DIR/talloc/$abi/libtalloc.so" ]; then
            cp "$OUTPUT_DIR/talloc/$abi/libtalloc.so" "$target_dir/libtalloc.so"
            log "  -> $abi/libtalloc.so"
        fi

        # libbsdtar.so
        if [ -f "$OUTPUT_DIR/bsdtar/$abi/bin/bsdtar" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/bin/bsdtar" "$target_dir/libbsdtar.so"
            log "  -> $abi/libbsdtar.so"
        elif [ -f "$OUTPUT_DIR/bsdtar/$abi/libbsdtar.so" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/libbsdtar.so" "$target_dir/libbsdtar.so"
            log "  -> $abi/libbsdtar.so"
        fi

        # libproot32.so (armeabi-v7a only)
        if [ "$abi" = "armeabi-v7a" ] && [ -f "$OUTPUT_DIR/proot/armeabi-v7a/libproot32.so" ]; then
            cp "$OUTPUT_DIR/proot/armeabi-v7a/libproot32.so" "$target_dir/libproot32.so"
            log "  -> $abi/libproot32.so"
        fi
    done
    log "Deployment complete"
}

# ──────────────────────────────────────────────
# Show info
# ──────────────────────────────────────────────
info() {
    echo "========================================"
    echo " Project-Terminal Native Lib Builder"
    echo "========================================"
    echo " NDK: $NDK_DIR"
    echo " CMake toolchain: $CMAKE_TOOLCHAIN"
    echo " ABIs: ${ABIS[*]}"
    echo " Jobs: $JOBS"
    echo " Output: $OUTPUT_DIR"
    echo "========================================"
}

usage() {
    echo "Usage: $0 [command]"
    echo "Commands:"
    echo "  all       - Build everything and deploy"
    echo "  talloc    - Build talloc only"
    echo "  bsdtar    - Build bsdtar only"
    echo "  proot     - Build PRoot (proot + proot-xed)"
    echo "  proot32   - Build 32-bit PRoot"
    echo "  deploy    - Deploy outputs to jniLibs"
    echo "  info      - Show configuration"
    echo ""
    echo "  Run with no args defaults to 'all'"
}

case "${1:-all}" in
    all)
        info
        check_prereqs
        build_talloc
        build_bsdtar
        build_proot
        build_proot32
        deploy
        log "All native libs built and deployed!"
        ;;
    talloc)   check_prereqs; build_talloc; deploy ;;
    bsdtar)   check_prereqs; build_bsdtar; deploy ;;
    proot)    check_prereqs; build_proot; deploy ;;
    proot32)  check_prereqs; build_proot32; deploy ;;
    deploy)   deploy ;;
    info)     info ;;
    *)        usage; exit 1 ;;
esac
