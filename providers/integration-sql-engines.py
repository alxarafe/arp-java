#!/usr/bin/env python3
"""Explicit opt-in engine integrations. Never called by the ordinary solution/tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time

IMAGES = {
    'postgresql': 'postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81',
    'mysql': 'mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902',
    'mariadb': 'mariadb@sha256:4ebbcf100a3ba320766dfa0e1af008df12261301db6ef72798c7650f70794aed',
    'sqlserver': ('mcr.microsoft.com/mssql/server@sha256:'
                  '4525e3efac9f3e241cb003bb2ff2a82818b502f37e12049d788b1a9fa7e13b7f'),
}
LABEL = 'arp.sql.java.002'


def run(*args, **kwargs):
    result = subprocess.run(args, text=True, capture_output=True, **kwargs)
    if result.returncode:
        # Never include credentials carried in docker environment arguments in an exception.
        raise subprocess.CalledProcessError(result.returncode, args[:2])
    return result.stdout.strip()


def inventory():
    ids = run('docker', 'ps', '-aq').split()
    return {i: json.loads(run('docker', 'inspect', i))[0]['State']['Status'] for i in ids}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--engine', required=True, choices=['mysql', 'mariadb', 'sqlserver', 'all'])
    p.add_argument('--listget-contract', type=Path, required=True)
    p.add_argument('--contract', type=Path, required=True)
    p.add_argument('--maven-cache', type=Path, help='Optional existing Maven repository to copy')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    output = args.output.expanduser().resolve()
    if not output.is_relative_to(Path.home() / 'Desarrollo/tmp'):
        p.error('Output must be under ~/Desarrollo/tmp')
    source = Path(__file__).resolve().parent.parent
    contract = args.listget_contract.resolve()
    if run('git', '-C', str(contract), 'rev-parse', 'HEAD') != (source / 'listget-contract.ref').read_text().strip():
        p.error('Wrong LIST/GET contract commit')
    if run('git', '-C', str(contract), 'status', '--porcelain'):
        p.error('Contract checkout must be clean')
    output.mkdir(parents=True, exist_ok=False)
    before = inventory()
    volumes = set(run('docker', 'volume', 'ls', '-q').split())
    files = run('git', '-C', str(source), 'ls-files', '--cached', '--others', '--exclude-standard').splitlines()
    manifest = {f: hashlib.sha256((source / f).read_bytes()).hexdigest() for f in files if (source / f).is_file()}
    (output / 'source-sha256.json').write_text(json.dumps(manifest, indent=2))
    build = output / 'source'
    shutil.copytree(source, build, ignore=shutil.ignore_patterns('.git', 'target', '__pycache__'))
    historical = args.contract.resolve()
    if run('git', '-C', str(historical), 'status', '--porcelain'):
        p.error('Historical contract checkout must be clean')
    if run('git', '-C', str(historical), 'rev-parse', 'HEAD') != (source / 'contract.ref').read_text().strip():
        p.error('Wrong historical contract commit')
    cache = output / 'm2'
    cache.mkdir()
    if args.maven_cache:
        shutil.copytree(args.maven_cache.resolve(), cache, dirs_exist_ok=True)
    results = []
    try:
        for engine in ['mysql', 'mariadb', 'sqlserver'] if args.engine == 'all' else [args.engine]:
            nonce = secrets.token_hex(12)
            password = 'Arp!A1' + secrets.token_hex(16)
            network = 'arp-sql-' + nonce
            container = None
            run('docker', 'network', 'create', '--label', f'{LABEL}={nonce}', network)
            env = {'postgresql': ['POSTGRES_DB=arpcds', 'POSTGRES_USER=arpcds', 'POSTGRES_PASSWORD=' + password],
                   'mysql': ['MYSQL_DATABASE=arpcds', 'MYSQL_ROOT_HOST=%', 'MYSQL_ROOT_PASSWORD=' + password],
                   'mariadb': ['MARIADB_DATABASE=arpcds', 'MARIADB_ROOT_HOST=%', 'MARIADB_ROOT_PASSWORD=' + password],
                   'sqlserver': ['ACCEPT_EULA=Y', 'MSSQL_PID=Developer', 'MSSQL_MEMORY_LIMIT_MB=2048',
                                 'MSSQL_SA_PASSWORD=' + password]}[engine]
            try:
                command = ['docker', 'run', '-d', '--network', network, '--network-alias', 'db',
                           '--label', f'{LABEL}={nonce}']
                for value in env:
                    command.extend(['-e', value])
                container = run(*command, IMAGES[engine])
                def sql(text):
                    if engine == 'postgresql':
                        return run('docker', 'exec', container, 'psql', '-U', 'arpcds', '-d', 'arpcds',
                                   '-v', 'ON_ERROR_STOP=1', '-c', text)
                    if engine == 'sqlserver':
                        return run('docker', 'exec', '-e', 'SQLCMDPASSWORD=' + password, container,
                                   '/opt/mssql-tools18/bin/sqlcmd', '-S', 'localhost', '-U', 'sa',
                                   '-C', '-b', '-Q', text)
                    return run('docker', 'exec', '-e', 'MYSQL_PWD=' + password, container,
                               'mariadb' if engine == 'mariadb' else 'mysql', '-h127.0.0.1', '-uroot', '-e', text)
                deadline = time.monotonic() + 180
                while True:
                    try:
                        sql('SELECT 1')
                        break
                    except subprocess.CalledProcessError:
                        if time.monotonic() > deadline:
                            raise RuntimeError('Database readiness timeout: ' + engine)
                        time.sleep(2)
                prefix = 'USE arpcds; '
                if engine == 'sqlserver':
                    sql('CREATE DATABASE arpcds')
                if engine == 'postgresql':
                    prefix = ''
                sql(prefix + 'CREATE TABLE __arp_cds_test_owner(token varchar(64)); '
                    + "INSERT INTO __arp_cds_test_owner VALUES ('" + nonce + "')")
                invocation = ['docker', 'run', '--rm', '--network', network, '--label', f'{LABEL}={nonce}',
                              '--user', f'{os.getuid()}:{os.getgid()}',
                              '-v', f'{build}:/src', '-v', f'{contract}:/listget:ro',
                              '-v', f'{historical}:/contract:ro', '-v', f'{cache}:/m2', '-w', '/src',
                              '-e', 'ARP_SQL_ENGINE=' + engine, '-e', 'ARP_DB_HOST=db',
                              '-e', 'ARP_DB_PASSWORD=' + password, '-e', 'ARP_CDS_RUN_TOKEN=' + nonce,
                              '-e', 'ARP_LISTGET_CONTRACT=/listget', '-e', 'MAVEN_CONFIG=/m2/config',
                              '-e', 'GIT_CONFIG_COUNT=2', '-e', 'GIT_CONFIG_KEY_0=safe.directory',
                              '-e', 'GIT_CONFIG_VALUE_0=/contract', '-e', 'GIT_CONFIG_KEY_1=safe.directory',
                              '-e', 'GIT_CONFIG_VALUE_1=/listget',
                              'maven:3.9-eclipse-temurin-25', 'mvn', '-B', '-Duser.home=/m2',
                              '-Dmaven.repo.local=/m2', '-Darp.contract.path=/contract',
                              '-P' + engine + '-provider', 'test']
                process = subprocess.run(invocation, text=True, capture_output=True, timeout=600)
                (output / (engine + '.log')).write_text(
                    (process.stdout + process.stderr).replace(password, '[redacted]'))
                results.append({'engine': engine, 'image': IMAGES[engine], 'exitCode': process.returncode})
                print(engine, 'PASS' if process.returncode == 0 else 'FAIL', flush=True)
            finally:
                if container:
                    metadata = json.loads(run('docker', 'inspect', container))[0]
                    if metadata['Config']['Labels'].get(LABEL) != nonce:
                        raise RuntimeError('Refusing to remove unowned resource')
                    run('docker', 'rm', '-fv', container)
                run('docker', 'network', 'rm', network)
    finally:
        after = inventory()
        preserved = all(after.get(i) == state for i, state in before.items())
        preserved = preserved and volumes.issubset(set(run('docker', 'volume', 'ls', '-q').split()))
        (output / 'summary.json').write_text(json.dumps(
            {'results': results, 'foreignResourcesPreserved': preserved,
             'completed': len(results) == (3 if args.engine == 'all' else 1)
             and all(r['exitCode'] == 0 for r in results)}, indent=2))
        if not preserved:
            raise RuntimeError('Foreign resource inventory changed')
    if any(r['exitCode'] != 0 for r in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()