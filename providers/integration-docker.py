#!/usr/bin/env python3
"""Owned ephemeral database integration runner; no ports, mounts or cleanup of other projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time

STACK = 'java'
REF = '7a62bba0e8f84dbc198e837baf63fc7e0d8722e1'
IMAGES = {'postgresql': 'postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81',
          'mariadb': 'mariadb@sha256:4ebbcf100a3ba320766dfa0e1af008df12261301db6ef72798c7650f70794aed'}


def run(*args, check=True):
    return subprocess.run(args, check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def docker(*args):
    return run('docker', *args).stdout.strip()


def inventory():
    ids = docker('ps', '-aq').split()
    if not ids:
        return {}
    return {c['Id']: (c['State']['Running'], c['State']['StartedAt'], c['RestartCount'])
            for c in json.loads(docker('inspect', *ids))}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--contract', type=Path, required=True, help='Clean exact contract checkout')
    p.add_argument('--output', type=Path, required=True, help='Reports outside the source repository')
    p.add_argument('--php-image', default='arp-cds-read-003-php-test:local')
    p.add_argument('--listget-contract', type=Path, help='Exact LISTGET contract checkout')
    options = p.parse_args()
    # This script lives under providers/ in every runtime repository.
    source = Path(__file__).resolve().parents[1]
    contract = options.contract.resolve()
    assert run('git', '-C', str(contract), 'rev-parse', 'HEAD').stdout.strip() == REF, 'Wrong contract revision'
    assert not run('git', '-C', str(contract), 'status', '--porcelain').stdout.strip(), 'Dirty contract'
    assert (source / 'contract.ref').read_text().strip() == REF, 'Wrong runtime pin'
    if options.listget_contract:
        expected = (source / 'listget-contract.ref').read_text().strip()
        actual = run('git', '-C', str(options.listget_contract), 'rev-parse', 'HEAD').stdout.strip()
        assert actual == expected, 'Wrong LISTGET contract revision'
        assert not run('git', '-C', str(options.listget_contract), 'status', '--porcelain').stdout.strip()
    output = options.output.resolve()
    assert not output.is_relative_to(source), 'Reports must be outside source tree'
    assert output.is_relative_to(Path.home() / 'Desarrollo/tmp'), 'Use ~/Desarrollo/tmp for auxiliary results'
    output.mkdir(parents=True, exist_ok=True)
    logical_sources = sorted((contract / 'examples/demo/cds').glob('*.yaml'))
    logical_hashes = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in logical_sources}
    before = inventory()
    preexisting_volumes = set(docker('volume', 'ls', '-q').split())
    token = secrets.token_hex(8)
    password = secrets.token_hex(20)
    label = 'arp.cds.read.003.run=' + token
    containers = []
    network = None
    volume = None
    engine = 'mariadb' if STACK == 'php' else 'postgresql'
    report = {'stack': STACK, 'contractSha': REF, 'databaseImage': IMAGES[engine], 'completed': False}
    try:
        network = docker('network', 'create', '--label', label, 'arp-cds-003-' + token)
        environment = (['POSTGRES_DB=arpcds', 'POSTGRES_USER=arpcds', 'POSTGRES_PASSWORD=' + password]
                       if engine == 'postgresql' else ['MARIADB_DATABASE=arpcds', 'MARIADB_ROOT_PASSWORD=' + password])
        args = ['create', '--name', 'arp-cds-003-db-' + token, '--label', label,
                '--network', network, '--network-alias', 'db']
        for setting in environment:
            args += ['-e', setting]
        db = docker(*args, IMAGES[engine])
        containers.append(db)
        report['databaseImageId'] = json.loads(docker('inspect', db))[0]['Image']
        if options.listget_contract:
            report['listgetContractSha'] = expected
        docker('start', db)
        deadline = time.monotonic() + 100
        ready = (['pg_isready', '-U', 'arpcds', '-d', 'arpcds'] if engine == 'postgresql'
                 else ['healthcheck.sh', '--connect', '--innodb_initialized'])
        while run('docker', 'exec', db, *ready, check=False).returncode != 0:
            if time.monotonic() > deadline:
                raise RuntimeError('Owned database failed readiness')
            time.sleep(1)
        # Bootstrap a nonce through the owned container, not through an arbitrary external SQL endpoint.
        marker = ("CREATE TABLE __arp_cds_test_owner(token varchar(32)); "
                  "INSERT INTO __arp_cds_test_owner VALUES ('" + token + "')")
        if engine == 'postgresql':
            docker('exec', db, 'psql', '-U', 'arpcds', '-d', 'arpcds', '-v', 'ON_ERROR_STOP=1', '-c', marker)
        else:
            docker('exec', '-e', 'MYSQL_PWD=' + password, db, 'mariadb', '-uroot', 'arpcds', '-e', marker)
        volume_name = 'arp-cds-003-yaml-' + token
        assert volume_name not in preexisting_volumes, 'Refusing preexisting storage volume'
        volume = docker('volume', 'create', '--label', label, volume_name)
        assert json.loads(docker('volume', 'inspect', volume))[0]['Labels'].get('arp.cds.read.003.run') == token
        # The volume is owned by this invocation. No application/source directory is used as storage.
        setup = docker('create', '--label', label, '--entrypoint', 'sh', '--mount',
                       'type=volume,src=' + volume + ',dst=/physical-store',
                       'mcr.microsoft.com/dotnet/sdk:10.0', '-c',
                       'chown ' + str(os.getuid()) + ':' + str(os.getgid()) + ' /physical-store')
        containers.append(setup)
        docker('start', '-a', setup)
        assert json.loads(docker('inspect', setup))[0]['State']['ExitCode'] == 0, 'Volume setup failed'
        docker('rm', setup)
        containers.remove(setup)
        report['physicalStoreVolume'] = volume
        with tempfile.TemporaryDirectory(prefix='arp-cds-003-build-', dir=output) as temp:
            work = Path(temp)
            shutil.copytree(source, work, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('.git', 'bin', 'obj', 'target', 'vendor', 'artifacts',
                                                         '__pycache__', '*.pyc'))
            if STACK == 'dotnet':
                image = 'mcr.microsoft.com/dotnet/sdk:10.0'
                project = 'providers/tests/PostgreSql.Integration'
                prepare = 'dotnet restore ' + project + '/PostgreSql.Integration.csproj --locked-mode && '
                seed = ('dotnet test Arp.slnx -c Release && ' + prepare
                        + 'dotnet run --project ' + project + ' -c Release --no-restore && '
                        + 'dotnet run --project ' + project + ' -c Release --no-restore -- seed')
                recover = prepare + 'dotnet run --project ' + project + ' -c Release --no-restore -- recover'
            elif STACK == 'java':
                image = 'maven:3.9-eclipse-temurin-25'
                maven = ('mvn -B -Duser.home=/tmp -Dmaven.repo.local=/tmp/m2-repository '
                         '-Ppostgresql-provider -Darp.contract.path=/contract ')
                seed = maven + 'test'
                recover = maven + '-Dtest=PersistenceIntegrationTest test'
            else:
                image = options.php_image
                prepare = 'composer install --no-interaction --prefer-dist --no-plugins --no-scripts && '
                seed = (prepare + 'composer validate --no-check-publish && composer test && '
                        'php providers/mariadb/tests/integration.php && '
                        'php providers/mariadb/tests/persistence.php seed')
                recover = prepare + 'php providers/mariadb/tests/persistence.php recover'
            log = ''
            runner_ids = []
            for phase, command in [('seed', seed), ('recover', recover)]:
                args = ['create', '--name', 'arp-cds-003-' + phase + '-' + token, '--label', label,
                        '--network', network, '--user', str(os.getuid()) + ':' + str(os.getgid()),
                        '--entrypoint', 'sh', '-e', 'HOME=/tmp', '-e', 'DOTNET_CLI_HOME=/tmp',
                        '-e', 'COMPOSER_HOME=/tmp/composer', '-e', 'MAVEN_CONFIG=/tmp/maven-config',
                        '--mount', 'type=bind,src=' + str(work) + ',dst=/work',
                        '--mount', 'type=bind,src=' + str(contract) + ',dst=/contract,readonly',
                        '--mount', 'type=volume,src=' + volume + ',dst=/physical-store',
                        '-w', '/work', '-e', 'ARP_CONTRACT_PATH=/contract', '-e', 'ARP_DB_HOST=db',
                        '-e', 'ARP_DB_PASSWORD=' + password, '-e', 'ARP_CDS_RUN_TOKEN=' + token,
                        '-e', 'ARP_PHYSICAL_STORE=/physical-store', '-e', 'ARP_PERSIST_PHASE=' + phase,
                        '-e', 'COMPOSER_ROOT_VERSION=dev-integration', '-e', 'GIT_CONFIG_COUNT=1',
                        '-e', 'GIT_CONFIG_KEY_0=safe.directory', '-e', 'GIT_CONFIG_VALUE_0=/contract',
                        image, '-c', command]
                if options.listget_contract:
                    args[args.index(image):args.index(image)] = [
                        '--mount', 'type=bind,src=' + str(options.listget_contract.resolve()) + ',dst=/listget,readonly',
                        '-e', 'ARP_LISTGET_CONTRACT=/listget']
                runner = docker(*args)
                containers.append(runner)
                runner_ids.append(runner)
                try:
                    result = run('docker', 'start', '-a', runner, check=False)
                finally:
                    if json.loads(docker('inspect', runner))[0]['State']['Running']:
                        docker('kill', runner)
                phase_log = (result.stdout + '\n' + result.stderr).replace(password, '[redacted]')
                log += '\nPHASE: ' + phase + '\n' + phase_log
                (output / (STACK + '.log')).write_text(log)
                if options.listget_contract:
                    records = [json.loads(line.split('LISTGET_EVIDENCE:', 1)[1]) for line in log.splitlines()
                               if line.startswith('LISTGET_EVIDENCE:')]
                    (output / 'listget-responses.json').write_text(json.dumps(records, indent=2, ensure_ascii=False))
                state = json.loads(docker('inspect', runner))[0]
                if state['State']['ExitCode'] != 0:
                    raise RuntimeError('Tests failed; see ' + str(output / (STACK + '.log')))
                assert state['Config']['Labels'].get('arp.cds.read.003.run') == token
                assert runner not in before
                # Destroy seed container before creating its replacement with the SAME volume.
                docker('rm', '-v', runner)
                containers.remove(runner)
                assert run('docker', 'inspect', runner, check=False).returncode != 0
            assert len(set(runner_ids)) == 2
            assert 'PERSISTENCE_SEED:' in log and 'PERSISTENCE_RECOVERED:' in log
            report['distinctRunnerContainersDestroyed'] = runner_ids
            report['physicalYamlRecoveredAfterContainerRecreation'] = True
            report['recoveryWithoutIntrospection'] = True
            # All generated physical files must be on the external volume, never in either source tree.
            assert not list(work.rglob('*.physical.yaml')), 'Generated physical YAML in source copy'
            report['noGeneratedPhysicalYamlInSource'] = True
        report['completed'] = True
    finally:
        # Only IDs returned by this invocation, additionally guarded by its unique label.
        for identifier in reversed(containers):
            state = json.loads(docker('inspect', identifier))[0]
            assert identifier not in before, 'Refusing preexisting container'
            assert state['Config']['Labels'].get('arp.cds.read.003.run') == token, 'Ownership mismatch'
            docker('rm', '-f', '-v', identifier)
        if network is not None:
            state = json.loads(docker('network', 'inspect', network))[0]
            assert state['Labels'].get('arp.cds.read.003.run') == token, 'Network ownership mismatch'
            docker('network', 'rm', network)
        if volume is not None:
            state = json.loads(docker('volume', 'inspect', volume))[0]
            assert state['Labels'].get('arp.cds.read.003.run') == token, 'Volume ownership mismatch'
            docker('volume', 'rm', volume)
            report['ownedVolumeRemovedAfterRecoveryProof'] = True
        report['preexistingVolumesPreserved'] = preexisting_volumes.issubset(
            set(docker('volume', 'ls', '-q').split()))
        assert report['preexistingVolumesPreserved'], 'Preexisting volume disappeared'
        after = inventory()
        report['preexistingContainersPreserved'] = all(after.get(k) == v for k, v in before.items())
        report['logicalYamlUnchanged'] = all(hashlib.sha256(p.read_bytes()).hexdigest() == h
                                             for p, h in logical_hashes.items())
        report['testLogPersistsAfterContainerRemoval'] = (output / (STACK + '.log')).is_file()
        (output / (STACK + '-isolation.json')).write_text(json.dumps(report, indent=2))
        assert report['preexistingContainersPreserved'], 'Preexisting containers changed during the run'
        assert report['logicalYamlUnchanged'], 'Developer YAML changed'
        assert report['testLogPersistsAfterContainerRemoval'], 'Test report lost'
    print(json.dumps(report))


if __name__ == '__main__':
    main()