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
    "x86_64:x86_64-linux-android"
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
    if [ ! -f "$src_dir/talloc.c" ]; then
        log "talloc source not found at $src_dir"
        log "Please download https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz"
        exit 1
    fi

    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local host="${abi_entry#*:}"
        local install_dir="$OUTPUT_DIR/talloc/$abi"

        log "Building talloc for $abi..."
        mkdir -p "$install_dir/include"

        local toolchain="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
        local cc="$toolchain/${host}${API_LEVEL}-clang"
        local ar="$toolchain/llvm-ar"

        local build_abi="$BUILD_DIR/talloc/build_$abi"
        mkdir -p "$build_abi"

        pushd "$build_abi" >/dev/null
        local talloc_src="$src_dir/talloc-2.4.2"
        "$cc" -c -fPIC -Os \
            -I"$talloc_src" -I"$talloc_src/lib/replace" \
            -DTALLOC_BUILD_VERSION_MAJOR=2 \
            -DTALLOC_BUILD_VERSION_MINOR=4 \
            -DTALLOC_BUILD_VERSION_RELEASE=2 \
            -DHAVE___ATTRIBUTE__ \
            -DHAVE_VA_COPY \
            "$talloc_src/talloc.c" -o "talloc.o" 2>&1
        if [ -f "talloc.o" ]; then
            "$ar" rcs "$install_dir/libtalloc.a" "talloc.o"
            cp "$talloc_src/talloc.h" "$install_dir/include/"
            log "  -> $install_dir/libtalloc.a"
        else
            log "  ERROR: talloc compilation failed"
            return 1
        fi
        log "  -> $install_dir/libtalloc.a"
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
            -DENABLE_SHARED=ON \
            -DENABLE_STATIC=OFF \
            -DENABLE_LZMA=ON \
            -DENABLE_ZLIB=ON \
            -DENABLE_OPENSSL=OFF \
            -DENABLE_NETTLE=OFF \
            -DCMAKE_INSTALL_PREFIX="$install_dir" \
            -DCMAKE_BUILD_TYPE=Release

        cmake --build . --target bsdtar archive -- -j"$JOBS"

        # Install via cmake para obtener bsdtar y libarchive.so en install_dir
        cmake --install . --prefix "$install_dir" 2>/dev/null || {
            log "cmake --install failed, falling back to manual copy"
            if [ -f "bin/bsdtar" ]; then
                cp "bin/bsdtar" "$install_dir/bsdtar"
                log "bsdtar binary copied to $install_dir/bsdtar"
            fi

            # libarchive.so shared library for JNI extraction via archive_read_open_fd
            local archive_so=""
            for p in \
                "libarchive.so" \
                "bin/libarchive.so" \
                "lib/libarchive.so" \
                "libarchive/libarchive.so" \
                "src/.libs/libarchive.so" \
                "libarchive.so.19"; do
                if [ -f "$p" ]; then
                    archive_so="$p"
                    break
                fi
            done
            # Si no se encontró con nombre exacto, buscar recursivamente
            if [ -z "$archive_so" ]; then
                local found
                found=$(find "$build_abi" -name "libarchive.so*" -type f 2>/dev/null | head -1)
                if [ -n "$found" ]; then
                    if [[ "$found" == *.so.* ]]; then
                        local base="${found%.so*}"
                        ln -sf "$found" "${base}.so" 2>/dev/null || true
                        archive_so="${base}.so"
                    else
                        archive_so="$found"
                    fi
                fi
            fi
            if [ -n "$archive_so" ]; then
                cp "$archive_so" "$install_dir/libarchive.so"
                log "libarchive.so manually copied to $install_dir/libarchive.so"
            else
                log "WARNING: libarchive.so not found in build output (tried all known paths)"
                ls -la "$build_abi/" 2>/dev/null | head -20 || true
            fi
        }

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
        pushd "$src_dir" >/dev/null
        git submodule update --init 2>/dev/null || true
        popd >/dev/null
    fi

    # Create TLS alignment fix assembly (forces PT_TLS p_align >= 64 for ARM64 Bionic)
    local tls_fix_src="$BUILD_DIR/tls_align_fix.s"
    if [ ! -f "$tls_fix_src" ]; then
        cat > "$tls_fix_src" << 'EOF'
.section .tdata,"awT",%progbits
.p2align 6
.space 1
EOF
    fi

    for abi_entry in "${ABIS[@]}"; do
        local abi="${abi_entry%%:*}"
        local host="${abi_entry#*:}"
        local install_dir="$OUTPUT_DIR/proot/$abi"

        log "Building PRoot for $abi..."
        mkdir -p "$install_dir"

        local toolchain="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
        local cc="$toolchain/${host}${API_LEVEL}-clang"
        local ar="$toolchain/llvm-ar"
        local strip="$toolchain/llvm-strip"
        local objcopy="$toolchain/llvm-objcopy"
        local objdump="$toolchain/llvm-objdump"
        local talloc_install="$OUTPUT_DIR/talloc/$abi"
        local vpath="$src_dir/src"

        local cppflags="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$vpath -I$vpath/../lib/uthash/include -I$talloc_install/include"

        # Compile TLS alignment fix object for this ABI
        local tls_fix_obj="$BUILD_DIR/tls_align_fix_$abi.o"
        "$cc" -c -o "$tls_fix_obj" "$tls_fix_src" 2>&1

        local base_ldflags="-fPIE -pie -static -Wl,-z,noexecstack -L$talloc_install -ltalloc $tls_fix_obj"

        # Build proot variant (PROOT_NO_SECCOMP=1)
        log "  Building libproot.so ($abi)..."
        make -C "$src_dir/src" -j"$JOBS" clean 2>/dev/null || true
        make -C "$src_dir/src" -j"$JOBS" \
            CC="$cc" \
            LD="$cc" \
            AR="$ar" \
            STRIP="$strip" \
            OBJCOPY="$objcopy" \
            OBJDUMP="$objdump" \
            CPPFLAGS="$cppflags" \
            CFLAGS="-Os -fPIE -DPROOT_NO_SECCOMP=1 -Wno-implicit-function-declaration -Wno-int-conversion" \
            LDFLAGS="$base_ldflags" \
            V=0 \
            proot 2>&1 | tail -20

        if [ -f "$src_dir/src/proot" ]; then
            cp "$src_dir/src/proot" "$install_dir/libproot.so"
            log "  -> $install_dir/libproot.so"
        fi

        # Build proot-xed variant (without PROOT_NO_SECCOMP, so seccomp enabled)
        log "  Building libproot-xed.so ($abi)..."
        make -C "$src_dir/src" -j"$JOBS" clean 2>/dev/null || true
        make -C "$src_dir/src" -j"$JOBS" \
            CC="$cc" \
            LD="$cc" \
            AR="$ar" \
            STRIP="$strip" \
            OBJCOPY="$objcopy" \
            OBJDUMP="$objdump" \
            CPPFLAGS="$cppflags" \
            CFLAGS="-Os -fPIE -Wno-implicit-function-declaration -Wno-int-conversion" \
            LDFLAGS="$base_ldflags" \
            V=0 \
            proot 2>&1 | tail -20

        if [ -f "$src_dir/src/proot" ]; then
            cp "$src_dir/src/proot" "$install_dir/libproot-xed.so"
            log "  -> $install_dir/libproot-xed.so"
        fi
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
        if [ -f "$OUTPUT_DIR/bsdtar/$abi/bsdtar" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/bsdtar" "$target_dir/libbsdtar.so"
            log "  -> $abi/libbsdtar.so"
        elif [ -f "$OUTPUT_DIR/bsdtar/$abi/bin/bsdtar" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/bin/bsdtar" "$target_dir/libbsdtar.so"
            log "  -> $abi/libbsdtar.so"
        elif [ -f "$OUTPUT_DIR/bsdtar/$abi/libbsdtar.so" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/libbsdtar.so" "$target_dir/libbsdtar.so"
            log "  -> $abi/libbsdtar.so"
        fi

        # libarchive.so (shared library for JNI extraction)
        if [ -f "$OUTPUT_DIR/bsdtar/$abi/libarchive.so" ]; then
            cp "$OUTPUT_DIR/bsdtar/$abi/libarchive.so" "$target_dir/libarchive.so"
            log "  -> $abi/libarchive.so"
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
        deploy
        log "All native libs built and deployed!"
        ;;
    talloc)   check_prereqs; build_talloc; deploy ;;
    bsdtar)   check_prereqs; build_bsdtar; deploy ;;
    proot)    check_prereqs; build_proot; deploy ;;
    deploy)   deploy ;;
    info)     info ;;
    *)        usage; exit 1 ;;
esac
