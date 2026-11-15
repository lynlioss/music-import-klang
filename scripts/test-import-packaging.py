#!/usr/bin/env python3
"""Exercise the release packaging guard without building an APK."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('verify-import-packaging.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ImportPackagingTest(unittest.TestCase):
    def mapping(self, omit_constructor=None):
        return '\n'.join(
            'org.apache.commons.compress.archivers.zip.' + name + ' -> a' + str(index) + ':\n' +
            ('    void <init>() -> <init>' if name != omit_constructor else '    int getHeaderId() -> a')
            for index, name in enumerate(guard.EXTRA_FIELDS)
        )

    def test_preserved_constructors_pass(self):
        guard.verify_mapping(self.mapping())

    def test_missing_reflective_constructor_fails(self):
        with self.assertRaisesRegex(AssertionError, 'no-arg constructor'):
            guard.verify_mapping(self.mapping('AsiExtraField'))

    def test_missing_handler_fails(self):
        with self.assertRaisesRegex(AssertionError, 'Missing reflected ZIP handler'):
            guard.verify_mapping('')

    def test_multi_abi_package_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                for name in ('libpython.so', 'libpython.zip.so', 'libqjs.so', 'libffmpeg.so', 'libffmpeg.zip.so'):
                    archive.writestr('lib/arm64-v8a/' + name, 'data')
            guard.verify_apk(apk)
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('lib/x86_64/libpython.so', 'data')
            with self.assertRaisesRegex(AssertionError, 'Unexpected release ABIs'):
                guard.verify_apk(apk)


if __name__ == '__main__':
    unittest.main()
