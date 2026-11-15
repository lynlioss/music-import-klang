#!/usr/bin/env python3
import json
import re
from pathlib import Path
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'plugin.json').read_text())
assert manifest == json.loads((root / 'plugin/src/main/res/raw/plugin.json').read_text())
build = (root / 'plugin/build.gradle.kts').read_text()
assert f'versionName = "{manifest["version"]}"' in build
assert manifest['apiVersion'] == 1 and manifest['type'] == 'musicImport'
assert manifest['musicImport']['package'] in build
assert 'GPL' in (root / 'README.md').read_text()
print('Plugin manifest, embedded manifest, version and package agree')
