#!/usr/bin/env python3
"""Reject APKs missing the HIF JNI library, ABI dependencies or 16KB ELF alignment.

Requires GNU readelf or --readelf pointing to the NDK's llvm-readelf.
This is an artifact check, NOT a real-device decoding test.
"""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile


def check(apk, readelf):
    abis = {"arm64-v8a": 183}
    required = {"libalpha_heif.so", "libheif.so", "libde265.so"}
    platform = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libjnigraphics.so",
                "libandroid.so", "libz.so", "libatomic.so"}
    symbols = ["Java_com_letter_alphapro_core_heif_HeifDecoder_" + name for name in
               ("nativeDecodeFile", "nativeDecodeThumbnail", "nativeDecodePreview", "nativeDecodeFilePreview", "nativeReadExif")]
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="alphapro-elf-") as work:
        packaged_abis = {name.split("/")[1] for name in archive.namelist()
                         if name.startswith("lib/") and name.endswith(".so")}
        assert packaged_abis == set(abis), f"Unexpected packaged ABIs: {packaged_abis}"
        for abi, machine in abis.items():
            libraries = {Path(name).name: name for name in archive.namelist()
                         if name.startswith("lib/" + abi + "/") and name.endswith(".so")}
            assert required <= libraries.keys(), f"{abi}: missing {required - libraries.keys()}"
            for name, entry in libraries.items():
                data = archive.read(entry)
                assert data[:4] == b"\x7fELF", entry
                endian = "<" if data[5] == 1 else ">"
                assert struct.unpack_from(endian + "H", data, 18)[0] == machine, entry
                if data[4] == 2:
                    offset = struct.unpack_from(endian + "Q", data, 32)[0]
                    size, count = struct.unpack_from(endian + "HH", data, 54)
                    alignment_format, alignment_offset = "Q", 48
                else:
                    offset = struct.unpack_from(endian + "I", data, 28)[0]
                    size, count = struct.unpack_from(endian + "HH", data, 42)
                    alignment_format, alignment_offset = "I", 28
                loads = []
                for i in range(count):
                    position = offset + i * size
                    if struct.unpack_from(endian + "I", data, position)[0] == 1:
                        loads.append(struct.unpack_from(endian + alignment_format, data,
                                                        position + alignment_offset)[0])
                assert loads and min(loads) >= 16384, f"{entry}: LOAD alignment {loads}"
                # Current AGP uses extracted native libs, not mmap of unaligned ZIP entries.
                assert archive.getinfo(entry).compress_type != zipfile.ZIP_STORED, entry
                local = Path(work) / (abi + "-" + name)
                local.write_bytes(data)
                dynamic = subprocess.check_output([readelf, "-d", str(local)], text=True)
                needed = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic))
                assert needed <= libraries.keys() | platform, f"{entry}: missing deps {needed - libraries.keys() - platform}"
                if name == "libalpha_heif.so":
                    exported = subprocess.check_output([readelf, "--dyn-syms", "--wide", str(local)], text=True)
                    for symbol in symbols:
                        assert any(line.split()[-1:] == [symbol] and " UND " not in line
                                   for line in exported.splitlines()), f"{entry}: missing {symbol}"
                print(f"{entry}: ELF/ABI/16KB/dependencies OK")
    print("HIF APK contract passed for arm64-v8a only; device decoding remains to be verified.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    check(args.apk, args.readelf)
