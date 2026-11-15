#!/usr/bin/env python3
"""Guard the release against missing reflective ZIP constructors and multi-ABI runtime bloat."""
import argparse
import re
import zipfile
from pathlib import Path

EXTRA_FIELDS = (
    'AsiExtraField', 'X5455_ExtendedTimestamp', 'X7875_NewUnix', 'JarMarker',
    'UnicodePathExtraField', 'UnicodeCommentExtraField', 'Zip64ExtendedInformationExtraField',
    'X000A_NTFS', 'X0014_X509Certificates', 'X0015_CertificateIdForFile',
    'X0016_CertificateIdForCentralDirectory', 'X0017_StrongEncryptionHeader',
    'X0019_EncryptionRecipientCertificateList',
)


def verify_mapping(text):
    sections = {}
    current = None
    for line in text.splitlines():
        match = re.fullmatch(r'(\S+) -> (\S+):', line)
        if match:
            current = match.group(1)
            sections[current] = [match.group(2), []]
        elif current and line.startswith('    '):
            sections[current][1].append(line.strip())
    for name in EXTRA_FIELDS:
        original = 'org.apache.commons.compress.archivers.zip.' + name
        assert original in sections, f'Missing reflected ZIP handler: {original}'
        renamed, members = sections[original]
        assert not renamed.startswith('R8$$REMOVED$$'), f'ZIP handler was removed: {original}'
        assert any(re.search(r'\bvoid <init>\(\)', entry) for entry in members), \
            f'Reflected no-arg constructor was removed: {original}'


def verify_apk(path):
    # The ARM64 Python/FFmpeg bundle is substantial; reject accidentally restoring four copies.
    assert path.stat().st_size < 90 * 1024 * 1024, 'Phone APK exceeds 90 MiB; check bundled runtime ABIs'
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        abis = {name.split('/')[1] for name in names if name.startswith('lib/')}
        assert abis == {'arm64-v8a'}, f'Unexpected release ABIs: {abis}'
        for name in ('libpython.so', 'libpython.zip.so', 'libqjs.so', 'libffmpeg.so', 'libffmpeg.zip.so'):
            assert 'lib/arm64-v8a/' + name in names, f'Missing download engine: {name}'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--mapping', type=Path, default=Path('app/build/outputs/mapping/release/mapping.txt'))
    args = parser.parse_args()
    verify_mapping(args.mapping.read_text())
    verify_apk(args.apk)
    print('Import engine: reflective ZIP constructors retained; ARM64 bundle; size limit passed')
