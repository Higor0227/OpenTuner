#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Creates the release signing key (RSA 2048) and a self-signed certificate valid for 30 years.
usage: genkey.py <key.pem> <cert.der>"""
import datetime
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

key_path, cert_path = sys.argv[1:3]
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "OpenTuner"),
                  x509.NameAttribute(NameOID.COUNTRY_NAME, "BR")])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder()
        .subject_name(name).issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365 * 30))
        .sign(key, hashes.SHA256()))
open(key_path, "wb").write(key.private_bytes(serialization.Encoding.PEM,
                                             serialization.PrivateFormat.PKCS8,
                                             serialization.NoEncryption()))
open(cert_path, "wb").write(cert.public_bytes(serialization.Encoding.DER))
print("key and certificate written")
