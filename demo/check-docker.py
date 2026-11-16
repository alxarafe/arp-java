"""Docker lifecycle regressions against the real demo runner; no Docker mocks.

Run after demo/run build. Only this test's exact container IDs are removed by its finalizer.
"""
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
import uuid
from tempfile import TemporaryDirectory
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "demo/run"
IMAGE = "arp-demo-java:local-" + hashlib.sha256(str(ROOT).encode()).hexdigest()[:12]
PREFIX = "arp-demo-003-java-" + uuid.uuid4().hex[:12]
created = []


def docker(*args, check=True):
    return subprocess.run(["docker", *args], text=True, capture_output=True, check=check)


def inspect(identity):
    result = docker("inspect", identity, check=False)
    return json.loads(result.stdout)[0] if result.returncode == 0 else None


def signature(identity):
    item = inspect(identity)
    return (item["State"]["Status"], item["State"]["StartedAt"], item["RestartCount"])


def run_demo(command, name, port, success, contract=None):
    previous = inspect(name)
    result = subprocess.run([str(RUNNER), command], text=True, capture_output=True, env={
        **os.environ, "ARP_DEMO_CONTAINER": name, "ARP_DEMO_PORT": str(port),
        "ARP_DEMO_ORIGIN": "http://127.0.0.1:4198",
        **({"ARP_CONTRACT_PATH": str(contract)} if contract is not None else {})})
    current = inspect(name)
    if command in ("start", "check") and previous is None and current is not None:
        created.append(current["Id"])
    assert (result.returncode == 0) is success, (command, result.stdout, result.stderr)
    return result


def foreign(suffix, labels=(), publish=False):
    args = ["run", "--detach", "--name", PREFIX + suffix]
    for label in labels:
        args.extend(["--label", label])
    if publish:
        args.extend(["--publish", "127.0.0.1::8080"])
    identity = docker(*args, IMAGE, "sleep", "600").stdout.strip()
    created.append(identity)
    return identity


baseline = {identity: signature(identity) for identity in docker("ps", "-q").stdout.split()}
try:
    blocker = foreign("-port", publish=True)
    mapping = docker("port", blocker, "8080/tcp").stdout.strip()
    assert mapping.startswith("127.0.0.1:")
    occupied_port = int(mapping.rsplit(":", 1)[1])
    before = signature(blocker)
    for command in ("start", "check"):
        name = PREFIX + "-occupied-" + command
        run_demo(command, name, occupied_port, success=False)
        assert inspect(name) is None, (command, "failed start left a container")
        assert signature(blocker) == before

    # Even an existing container with BOTH demo labels is not owned by this start invocation.
    label_sets = [(), ("arp.demo.owner=" + str(ROOT),),
                  ("arp.demo=java-dictionary",),
                  ("arp.demo.owner=" + str(ROOT), "arp.demo=java-dictionary")]
    for index, labels in enumerate(label_sets):
        identity = foreign("-collision-" + str(index), labels)
        name = inspect(identity)["Name"].lstrip("/")
        before = signature(identity)
        run_demo("start", name, 0, success=False)
        assert signature(identity) == before
        if index < 3:
            run_demo("stop", name, 0, success=False)
            assert signature(identity) == before

    # Temporary copies only: prove a running host retains its startup snapshot if the mount changes.
    contract = Path(os.environ.get("ARP_CONTRACT_PATH", ROOT.parent / "arp-contract"))
    with TemporaryDirectory(prefix=PREFIX) as temporary:
        snapshot = Path(temporary)
        paths = ["fixtures/dictionary/v1/catalog.json", "fixtures/dictionary/v1/sources/portable-equivalent.json"]
        for path in paths:
            destination = snapshot / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(contract / path, destination)
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        name = PREFIX + "-success"
        run_demo("start", name, port, success=True, contract=snapshot)
        identity = inspect(name)["Id"]
        assert docker("port", identity, "8080/tcp").stdout.strip() == "127.0.0.1:" + str(port)
        url = "http://127.0.0.1:" + str(port) + "/demo/info"
        for attempt in range(50):
            try:
                with urlopen(url, timeout=2) as response:
                    assert response.status == 200
                    original_info = json.load(response)
                    break
            except OSError:
                time.sleep(0.2)
        else:
            raise AssertionError("Host did not become ready on configured port")
        (snapshot / paths[0]).write_text("invalid JSON in a temporary test copy")
        with urlopen(url, timeout=2) as response:
            assert json.load(response) == original_info
        run_demo("stop", name, port, success=True)
        assert inspect(identity) is None
        run_demo("stop", name, port, success=True)
    assert all(signature(identity) == state for identity, state in baseline.items())
    print("PASS: occupied-port start/check cleanup, name collisions, both ownership labels, "
          "configured loopback port, startup snapshot, start/stop, existing containers unchanged")
finally:
    for identity in reversed(created):
        docker("rm", "--force", identity, check=False)