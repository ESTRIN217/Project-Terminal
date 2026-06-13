#!/usr/bin/env bash
set -euo pipefail

# Fix TLS segment alignment in ARM64 ELF binaries for Android Bionic compatibility.
# Android Bionic on ARM64 requires PT_TLS p_align >= 64.
# Usage: fix_tls_align.sh <path-to-elf-binary>

if [ $# -lt 1 ]; then
    echo "Usage: $0 <elf-binary> [elf-binary...]"
    exit 1
fi

for binary in "$@"; do
    if [ ! -f "$binary" ]; then
        echo "Error: '$binary' not found"
        continue
    fi

    echo "Checking $binary..."

    # Check if this is an ARM64 ELF with TLS
    if ! llvm-readelf -l "$binary" 2>/dev/null | grep -q "TLS"; then
        echo "  -> No TLS segment, skipping"
        continue
    fi

    # Get current TLS alignment
    current_align=$(llvm-readelf -l "$binary" 2>/dev/null | grep -A1 "TLS" | head -1 | awk '{print $NF}')
    if [ "$current_align" = "0x40" ] || [ "$current_align" = "64" ]; then
        echo "  -> TLS alignment already 64, skipping"
        continue
    fi

    echo "  -> Current TLS alignment: $current_align, fixing..."

    # Use Python to patch the ELF header
    python3 -c "
import struct, sys

with open('$binary', 'r+b') as f:
    # Read ELF header (64-bit)
    f.seek(0)
    ident = f.read(16)
    if ident[:4] != b'\\x7fELF':
        print('  -> Not an ELF file')
        sys.exit(1)

    # e_type (2), e_machine (2), e_version (4), e_entry (8), e_phoff (8), e_shoff (8)
    # e_flags (4), e_ehsize (2), e_phentsize (2), e_phnum (2), e_shentsize (2), e_shnum (2), e_shstrndx (2)
    f.seek(32)
    e_phoff = struct.unpack('<Q', f.read(8))[0]
    f.seek(54)
    e_phentsize = struct.unpack('<H', f.read(2))[0]
    e_phnum = struct.unpack('<H', f.read(2))[0]

    print(f'  -> Program headers at offset {e_phoff}, {e_phnum} entries, each {e_phentsize} bytes')

    patched = False
    for i in range(e_phnum):
        offset = e_phoff + i * e_phentsize
        f.seek(offset)
        p_type = struct.unpack('<I', f.read(4))[0]
        # PT_TLS = 7
        if p_type != 7:
            continue

        # For 64-bit ELF on ARM64:
        # p_type(4) + p_flags(4) + p_offset(8) + p_vaddr(8) + p_paddr(8) + p_filesz(8) + p_memsz(8) + p_align(8)
        # p_align is at offset offset + 48
        align_offset = offset + 48
        f.seek(align_offset)
        p_align = struct.unpack('<Q', f.read(8))[0]
        print(f'  -> Found TLS segment #{i}, current p_align = {p_align} (0x{p_align:x})')

        if p_align < 64:
            f.seek(align_offset)
            f.write(struct.pack('<Q', 64))
            print(f'  -> Patched p_align to 64 (0x40)')
            patched = True
        else:
            print(f'  -> p_align already >= 64, no change needed')
            patched = True  # still counts as success

    if not patched:
        print('  -> No TLS segment found')
        sys.exit(1)

    print('  -> Done')
" && echo "  -> Successfully patched $binary" || echo "  -> Failed to patch $binary"
done
