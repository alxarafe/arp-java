"""Disposable Git repositories only; never stage generated descriptors in the actual runtimes/contract."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

PROVIDERS = Path(__file__).resolve().parents[1]
CHECKER = PROVIDERS / 'check-generated-physical.py'
SPEC = importlib.util.spec_from_file_location('physical_guard', CHECKER)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)
PHYSICAL = 'apiVersion: arp.physical/v1\nid: storage.items\nkind: table\ncolumns: []\n'
LOGICAL = 'apiVersion: arp.logical/v1\nid: demo.items\nphysical: storage.items\nfields: {}\n'


class PhysicalGitGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='arp-git-physical-audit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run_git('init', '-q')
        (self.root / 'providers').mkdir()
        shutil.copyfile(CHECKER, self.root / 'providers/check-generated-physical.py')

    def run_git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE)

    def stage(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self.run_git('add', '-f', '--', name)

    def test_forced_ignored_yaml_is_rejected(self):
        self.stage('.gitignore', '*.physical.yaml\n')
        self.stage('cache/items.physical.yaml', PHYSICAL)
        self.assertEqual(guard.scan(self.root)[0][0], 'cache/items.physical.yaml')

    def test_index_is_authority_not_working_tree(self):
        self.stage('cache/model.yaml', PHYSICAL)
        (self.root / 'cache/model.yaml').write_text(LOGICAL)
        self.assertTrue(guard.scan(self.root))

    def test_renamed_extension_and_escaped_json_are_rejected(self):
        self.stage('innocent.txt', r'{"\u0061piVersion":"arp.\u0070hysical/v1"}')
        self.assertTrue(guard.scan(self.root))

    def test_flow_anchors_duplicate_keys_and_nested_documents_are_rejected(self):
        for text in [
            'version: &v arp.physical/v1\napiVersion: *v\n',
            '{apiVersion: arp.physical/v1}',
            'apiVersion: arp.logical/v1\napiVersion: arp.physical/v1\n',
            'envelope: [{apiVersion: arp.physical/v1}]',
            '---\na: 1\n---\napiVersion: arp.physical/v1\n',
        ]:
            with self.subTest(text=text):
                self.stage('model.yaml', text)
                self.assertTrue(guard.scan(self.root))

    def test_logical_projection_and_unrelated_yaml_are_allowed(self):
        self.stage('logical.yaml', LOGICAL)
        self.stage('projection.yaml', 'apiVersion: arp.projection/v1\nsource: {entity: demo.items}\nfields: [id]\n')
        self.stage('configuration.yml', 'name: test\nsettings: {enabled: true}\n')
        self.assertEqual(guard.scan(self.root), [])

    def test_reference_contract_is_not_scanned_or_modified(self):
        reference = self.root / 'separate-contract'
        reference.mkdir()
        subprocess.run(['git', '-C', str(reference), 'init', '-q'], check=True)
        fixture = reference / 'fixture.yaml'
        fixture.write_text(PHYSICAL)
        subprocess.run(['git', '-C', str(reference), 'add', 'fixture.yaml'], check=True)
        before = fixture.read_bytes()
        self.stage('logical.yaml', LOGICAL)
        self.assertEqual(guard.scan(self.root), [])
        self.assertEqual(fixture.read_bytes(), before)

    def test_whole_tree_catches_what_is_no_longer_staged(self):
        self.stage('renamed.yaml', PHYSICAL)
        tree = self.run_git('write-tree').decode().strip()
        self.run_git('rm', '--cached', '-f', 'renamed.yaml')
        self.assertEqual(guard.scan(self.root), [])
        self.assertTrue(guard.scan(self.root, tree))

    def test_special_filename_is_not_split_by_whitespace(self):
        name = 'cache/ñ with\ttab and\nnewline.yaml'
        self.stage(name, PHYSICAL)
        self.assertEqual(guard.scan(self.root)[0][0], name)

    def test_invalid_yaml_and_invalid_utf8_fail_closed(self):
        self.stage('broken.yaml', 'x: [')
        self.assertTrue(guard.scan(self.root))
        (self.root / 'broken.yaml').write_bytes(b'\xff')
        self.run_git('add', '-f', 'broken.yaml')
        self.assertTrue(guard.scan(self.root))

    def test_reserved_filename_rejected_even_without_version_marker(self):
        self.stage('CACHE/ITEMS.PHYSICAL.YML', 'columns: []\n')
        self.assertTrue(guard.scan(self.root))

    def test_binary_non_document_and_recursive_aliases_are_safe(self):
        (self.root / 'binary.bin').write_bytes(b'\0\xff')
        self.run_git('add', 'binary.bin')
        self.stage('configuration.yml', 'self: &s {again: *s}\n')
        self.assertEqual(guard.scan(self.root), [])

    def test_pre_commit_blocks_force_added_document_without_creating_commit(self):
        hooks = self.root / '.githooks'
        hooks.mkdir()
        hook = hooks / 'pre-commit'
        hook.write_bytes((PROVIDERS.parent / '.githooks/pre-commit').read_bytes())
        hook.chmod(0o755)
        self.run_git('config', 'core.hooksPath', '.githooks')
        self.run_git('config', 'user.name', 'Disposable audit')
        self.run_git('config', 'user.email', 'audit@example.invalid')
        self.stage('forced.yaml', PHYSICAL)
        environment = dict(os.environ, ARP_AUDIT_PYTHON=sys.executable)
        result = subprocess.run(['git', '-C', str(self.root), 'commit', '-m', 'must be rejected'],
                                env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'REJECT', result.stderr)
        self.assertNotEqual(subprocess.run(['git', '-C', str(self.root), 'rev-parse', '--verify', 'HEAD'],
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode, 0)


if __name__ == '__main__':
    unittest.main()