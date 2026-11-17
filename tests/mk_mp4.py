#!/usr/bin/env python3
"""Genera archivos MP4 sinteticos para probar Mp4.java (parser de cajas)."""
import struct, sys

def box(t, payload):
    return struct.pack(">I", 8 + len(payload)) + t + payload

def mvhd_v0(duration_s, timescale=1000):
    payload = (b"\x00\x00\x00\x00"          # version+flags
               + struct.pack(">II", 0, 0)   # ctime, mtime
               + struct.pack(">II", timescale, int(duration_s * timescale))
               + struct.pack(">i", 0x00010000)  # rate
               + struct.pack(">h", 0x0100)      # volume
               + b"\x00" * 10               # reserved
               + struct.pack(">9i", 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
               + b"\x00" * 24               # predefined
               + struct.pack(">I", 3))      # next track id
    return box(b"mvhd", payload)

def tkhd(width, height):
    payload = (b"\x00\x00\x00\x07"           # version 0 + flags
               + struct.pack(">II", 0, 0)    # ctime mtime
               + struct.pack(">II", 1, 0)    # trackid, reserved
               + struct.pack(">I", 3720000)  # duration
               + b"\x00" * 8                 # reserved
               + struct.pack(">hh", 0, 0)    # layer, alt group
               + struct.pack(">h", 0x0100)   # volume
               + struct.pack(">h", 0)        # reserved
               + struct.pack(">9i", 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
               + struct.pack(">II", width << 16, height << 16))
    return box(b"tkhd", payload)

def moov(duration_s, width, height):
    trak = box(b"trak", tkhd(width, height))
    return box(b"moov", mvhd_v0(duration_s) + trak)

def mp4_head(duration_s, width, height, filler_mb=1):
    ftyp = box(b"ftyp", b"isom" + b"\x00\x00\x02\x00" + b"isomiso2avc1mp41")
    mdat = box(b"mdat", b"\x00" * (filler_mb * 1024 * 1024))
    return ftyp + moov(duration_s, width, height) + mdat

def mp4_tail(duration_s, width, height, filler_mb=1):
    ftyp = box(b"ftyp", b"isom" + b"\x00\x00\x02\x00" + b"isomiso2avc1mp41")
    mdat = box(b"mdat", b"\xAB" * (filler_mb * 1024 * 1024))
    return ftyp + mdat + moov(duration_s, width, height)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/mp4tests"
    import os
    os.makedirs(out, exist_ok=True)
    # 3723 s = 1h 02min 03 s ; 426x240
    open(f"{out}/head.mp4", "wb").write(mp4_head(3723, 426, 240))
    open(f"{out}/tail.mp4", "wb").write(mp4_tail(7322, 1280, 720))
    print("ok", out)
