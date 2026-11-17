#!/usr/bin/env python3
"""Reject physical descriptor blobs from a runtime index or committed tree, independently of .gitignore."""
import argparse
from pathlib import Path
import subprocess
import sys

try:
    import yaml
    from yaml.nodes import MappingNode, ScalarNode, SequenceNode
except ImportError:
    raise SystemExit('Install providers/requirements-audit.txt in a development virtual environment') from None

MAX_BYTES = 4 * 1024 * 1024
MAX_NODES = 40000
RESERVED = ('.physical.yaml', '.physical.yml', '.physical.json')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE)


def contains_physical(node):
    pending = [node]
    seen = set()
    while pending:
        item = pending.pop()
        if item is None or id(item) in seen:
            continue
        seen.add(id(item))
        if len(seen) > MAX_NODES:
            raise ValueError('Unbounded YAML graph')
        if isinstance(item, MappingNode):
            for key, value in item.value:
                if (isinstance(key, ScalarNode) and key.value == 'apiVersion'
                        and isinstance(value, ScalarNode) and value.value.strip().startswith('arp.physical/')):
                    return True
                pending.extend((key, value))
        elif isinstance(item, SequenceNode):
            pending.extend(item.value)
    return False


def inspect_blob(path, mode, data):
    lower = path.casefold()
    if lower.endswith(RESERVED):
        return 'reserved generated-physical filename'
    strict = lower.endswith(('.yaml', '.yml', '.json'))
    if mode == '120000':
        return 'structured document symlink cannot be verified' if strict else None
    if len(data) > MAX_BYTES:
        return 'blob exceeds the bounded audit budget; cannot verify safely'
    try:
        text = data.decode('utf-8-sig')
    except UnicodeError:
        return 'structured document is not UTF-8' if strict else None
    if '\0' in text:
        return 'structured document contains NUL' if strict else None
    try:
        # Compose nodes only: never construct arbitrary tagged objects or coerce numeric values.
        for document in yaml.compose_all(text, Loader=yaml.BaseLoader):
            if contains_physical(document):
                return 'physical descriptor must remain outside versioned runtime source'
    except (ValueError, RecursionError):
        return 'document exceeds the bounded graph audit; cannot verify safely'
    except yaml.YAMLError:
        return 'structured document cannot be verified' if strict else None
    return None


def scan(root, tree=None):
    if tree is None:
        records = git(root, 'ls-files', '--stage', '-z').split(b'\0')
    else:
        records = git(root, 'ls-tree', '-r', '-z', '--full-tree', tree).split(b'\0')
    rejected = []
    for record in records:
        if not record:
            continue
        header, raw_path = record.split(b'\t', 1)
        mode, middle, final = header.decode('ascii').split()
        path = raw_path.decode('utf-8', errors='surrogateescape')
        if tree is None:
            oid = middle
            if final != '0':
                rejected.append((path, 'unmerged index cannot be verified'))
                continue
        else:
            oid = final
        if mode == '160000':
            continue  # Separate repository ownership, including an explicitly pinned contract checkout.
        size = int(git(root, 'cat-file', '-s', oid))
        if size > MAX_BYTES:
            reason = 'blob exceeds the bounded audit budget; cannot verify safely'
        else:
            reason = inspect_blob(path, mode, git(root, 'cat-file', 'blob', oid))
        if reason:
            rejected.append((path, reason))
    return rejected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group()
    choice.add_argument('--index', action='store_true', help='Inspect staged blobs (default)')
    choice.add_argument('--tree', help='Inspect the complete named tree, e.g. HEAD in CI')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    rejected = scan(root, args.tree)
    for path, reason in rejected:
        print(f'REJECT {path!r}: {reason}', file=sys.stderr)
    if rejected:
        return 1
    print('PASS: no versioned physical descriptors in ' + ('tree ' + args.tree if args.tree else 'index'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())