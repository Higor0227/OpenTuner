#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Packs an aapt2-linked APK with classes.dex, 4-byte aligns stored entries and signs it
with APK Signature Scheme v2 (RSA PKCS#1 v1.5 + SHA-256).

usage: apkpack.py <linked.apk> <classes.dex> <key.pem> <cert.der> <out.apk>
"""
import hashlib
import io
import struct
import sys
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography import x509

STORED = {"resources.arsc"}
ALIGN = 4
SIG_RSA_PKCS1_SHA256 = 0x0103
APK_SIG_V2_ID = 0x7109871A


def aligned_zip(src_apk, dex_path):
    entries = []
    with zipfile.ZipFile(src_apk) as z:
        for info in z.infolist():
            entries.append((info.filename, z.read(info.filename)))
    entries.insert(1, ("classes.dex", open(dex_path, "rb").read()))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as out:
        for name, data in entries:
            zi = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 2))
            zi.create_system = 0
            zi.external_attr = 0
            if name in STORED:
                zi.compress_type = zipfile.ZIP_STORED
                header_off = buf.tell()
                base = header_off + 30 + len(name.encode())
                # alignment extra field 0xD935: id, size, alignment, zero padding
                pad = (ALIGN - (base + 6) % ALIGN) % ALIGN
                zi.extra = struct.pack("<HHH", 0xD935, 2 + pad, ALIGN) + b"\0" * pad
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(zi, data)
    return buf.getvalue()


def find_eocd(apk):
    idx = apk.rfind(b"PK\x05\x06")
    if idx < 0:
        raise ValueError("EOCD not found")
    cd_size, cd_off = struct.unpack("<II", apk[idx + 12:idx + 20])
    return idx, cd_off, cd_size


def lp(b):
    return struct.pack("<I", len(b)) + b


def content_digest(sections):
    chunks = []
    for sec in sections:
        for i in range(0, len(sec), 1 << 20):
            c = sec[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()


def sign_v2(apk, key, cert_der):
    eocd_idx, cd_off, cd_size = find_eocd(apk)
    assert cd_off + cd_size == eocd_idx, "unexpected data between central directory and EOCD"
    contents = apk[:cd_off]
    cd = apk[cd_off:eocd_idx]
    eocd = apk[eocd_idx:]
    digest = content_digest([contents, cd, eocd])

    pub = key.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)
    digests = lp(lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + lp(digest)))
    certs = lp(lp(cert_der))
    attrs = lp(b"")
    signed_data = digests + certs + attrs
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signer = lp(signed_data) + lp(lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + lp(sig))) + lp(pub)
    value = lp(lp(signer))
    pair = struct.pack("<QI", 4 + len(value), APK_SIG_V2_ID) + value
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"

    new_eocd = bytearray(eocd)
    struct.pack_into("<I", new_eocd, 16, cd_off + len(block))
    return contents + block + cd + bytes(new_eocd)


def main():
    src, dex, key_path, cert_path, out = sys.argv[1:6]
    key = serialization.load_pem_private_key(open(key_path, "rb").read(), password=None)
    cert_der = open(cert_path, "rb").read()
    x509.load_der_x509_certificate(cert_der)  # sanity check
    apk = aligned_zip(src, dex)
    signed = sign_v2(apk, key, cert_der)
    open(out, "wb").write(signed)
    print(f"wrote {out} ({len(signed)} bytes)")


if __name__ == "__main__":
    main()
