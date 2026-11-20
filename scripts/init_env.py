#!/usr/bin/env python3
"""Create local-only demo credentials without printing them."""
from pathlib import Path
import secrets
path=Path('.env')
if path.exists():raise SystemExit('.env already exists; preserving its values.')
with path.open('x') as out:out.write('ADMIN_TOKEN='+secrets.token_hex(32)+'\nDATABASE_PASSWORD='+secrets.token_hex(32)+'\n')
path.chmod(0o600)
print('Created .env with separate random operator and database credentials.')
