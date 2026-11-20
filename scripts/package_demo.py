#!/usr/bin/env python3
"""Copy the shared UI; only change its explicit runtime mode."""
from pathlib import Path
import shutil
root=Path(__file__).resolve().parents[1]
source=root/'src/main/resources/static'
target=root/'docs'
for path in source.iterdir():
    if path.is_file():
        if path.name=='index.html':
            (target/path.name).write_text(path.read_text().replace('data-mode="live"','data-mode="browser"'))
        else:shutil.copyfile(path,target/path.name)
print('Packaged browser sandbox from the shared UI')
