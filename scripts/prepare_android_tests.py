#!/usr/bin/env python3
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
tls = root / '.local/tls'
tls.mkdir(parents=True, exist_ok=True)
config = tls / 'openssl.cnf'
config.write_text('''[req]
distinguished_name=dn
x509_extensions=extensions
prompt=no
[dn]
CN=Local emulator fixture
[extensions]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,digitalSignature,keyEncipherment
subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost
''')
subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
                '-keyout', str(tls/'key.pem'), '-out', str(tls/'cert.pem'), '-config', str(config)],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
(tls/'key.pem').chmod(0o600)
assets = root / 'app/src/androidTest/assets'
assets.mkdir(parents=True, exist_ok=True)
(assets/'test-ca.pem').write_bytes((tls/'cert.pem').read_bytes())
print('Created test-only CA asset; release app never includes it.')
