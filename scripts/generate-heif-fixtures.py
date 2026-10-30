#!/usr/bin/env python3
"""Generate synthetic Main-10 HEIF 4:2:0/4:2:2 regression fixtures with local FFmpeg.

No camera/user image or network is used. MP4 hvcC + single HEVC sample are placed
in a minimal ISO HEIF item container; chroma and bit depth are checked in hvcC.
"""
import argparse
from pathlib import Path
import struct
import subprocess
import tempfile


def box(kind, payload):
    return struct.pack('>I4s', len(payload) + 8, kind.encode()) + payload


def full(kind, version, payload):
    return box(kind, bytes([version, 0, 0, 0]) + payload)


def top_boxes(data):
    offset = 0
    while offset + 8 <= len(data):
        size = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset + 4:offset + 8]
        header = 8
        if size == 1:
            size = struct.unpack_from('>Q', data, offset + 8)[0]
            header = 16
        if size == 0:
            size = len(data) - offset
        if size < header or offset + size > len(data):
            raise ValueError('Invalid generated MP4 box')
        yield kind, data[offset + header:offset + size]
        offset += size


def make_fixture(output, width, height, chroma):
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='alphapro-heif-fixture-') as work:
        movie = Path(work) / 'frame.mp4'
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
                        '-f', 'lavfi', '-i', f'color=c=0x37a4bf:s={width}x{height}:r=1',
                        '-frames:v', '1', '-pix_fmt', f'yuv{chroma}p10le',
                        '-c:v', 'libx265', '-preset', 'ultrafast',
                        '-x265-params', 'pools=none:frame-threads=1:log-level=error:repeat-headers=0',
                        '-tag:v', 'hvc1', str(movie)], check=True)
        data = movie.read_bytes()
        marker = data.index(b'hvcC')
        size = struct.unpack_from('>I', data, marker - 4)[0]
        hvcc = data[marker - 4:marker - 4 + size]
        record = hvcc[8:]
        assert record[0] == 1 and record[16] & 3 == (2 if chroma == '422' else 1)
        assert record[17] & 7 == 2 and record[18] & 7 == 2, 'Not 10-bit'
        samples = [payload for kind, payload in top_boxes(data) if kind == b'mdat']
        assert len(samples) == 1 and samples[0]
        sample = samples[0]
        ftyp = box('ftyp', b'heix' + struct.pack('>I', 0) + b'mif1heix')
        properties = box('iprp', box('ipco', hvcc + full('ispe', 0, struct.pack('>II', width, height)) +
                                    full('pixi', 0, bytes([3, 10, 10, 10]))) +
                         full('ipma', 0, struct.pack('>IH', 1, 1) + bytes([3, 0x81, 0x82, 0x03])))
        def meta(offset):
            return full('meta', 0,
                        full('hdlr', 0, bytes(4) + b'pict' + bytes(12) + b'AlphaPro fixture\0') +
                        full('pitm', 0, struct.pack('>H', 1)) +
                        full('iloc', 0, bytes([0x44, 0]) + struct.pack('>HHHHII', 1, 1, 0, 1, offset, len(sample))) +
                        full('iinf', 0, struct.pack('>H', 1) + full('infe', 2, struct.pack('>HH', 1, 0) + b'hvc1Image\0')) +
                        properties)
        header = meta(0)
        header = meta(len(ftyp) + len(header) + 8)
        output.write_bytes(ftyp + header + box('mdat', sample))
        print(f'{output}: {width}x{height}, 10-bit 4:{chroma[1]}:{chroma[2]}, {output.stat().st_size} bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('core/heif/src/test/assets'))
    args = parser.parse_args()
    make_fixture(args.output / 'synthetic-420-10bit.heic', 640, 480, '420')
    make_fixture(args.output / 'synthetic-422-10bit.heic', 640, 480, '422')
    make_fixture(args.output / 'synthetic-422-24mp.heic', 6000, 4000, '422')
