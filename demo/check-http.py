"""Real localhost host/library checks; no mocks and no modified contract fixtures."""
import hashlib
import json
import os
import time
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

RUNTIME = os.environ.get("ARP_DEMO_RUNTIME", "java")
BASE = os.environ.get("ARP_DEMO_URL", "http://127.0.0.1:5200")
ORIGIN = os.environ.get("ARP_DEMO_ORIGIN", "http://127.0.0.1:4173")
ROOT = Path(os.environ["ARP_CONTRACT_PATH"])


def request(path, body=None, origin=ORIGIN, method=None, content_type="application/json"):
    headers = {"Origin": origin, "Content-Type": content_type}
    req = Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = urlopen(req, timeout=3)
    except HTTPError as error:
        response = error
    with response:
        text = response.read().decode()
        return response.status, response.headers, text


def validate(identity, value):
    status, _, text = request(f"/demo/dictionary/elements/{identity}/validate",
                              json.dumps({"value": value}).encode())
    return status, json.loads(text)


for attempt in range(50):
    try:
        status, headers, text = request("/demo/info")
        if status == 200:
            break
    except (URLError, TimeoutError, ConnectionError):
        pass
    time.sleep(0.2)
else:
    raise AssertionError("Host did not become ready")
info = json.loads(text)
assert info["demoProtocol"] == "arp-demo-" + RUNTIME + "/1" and info["runtime"] == RUNTIME
assert set(info["capabilities"]) == {"dictionary.list", "dictionary.describe", "dictionary.validate"}
assert headers["Access-Control-Allow-Origin"] == ORIGIN
for source in info["sources"]:
    assert source["sha256"] == hashlib.sha256((ROOT / source["path"]).read_bytes()).hexdigest()
status, _, text = request("/demo/dictionary/elements")
assert status == 200 and len(json.loads(text)["elements"]) == 18
status, _, text = request("/demo/dictionary/elements/portable.equivalent")
assert status == 200
assert json.loads(text, parse_float=Decimal)["constraints"]["maximumLength"] == Decimal("1e127")
# JSON numeric value equality matters; exponent capitalization is not contractual.
vectors = json.loads((ROOT / "fixtures/dictionary/v1/values.json").read_text())
for vector in (v for v in vectors if v["id"].startswith("type-")):
    status, result = validate(vector["element"]["id"], vector["value"])
    assert status == 200 and result == vector["expected"], (vector["id"], result)
for identity, value, valid, normalized in [
    ("test.int64", "9223372036854775807", True, "9223372036854775807"),
    ("test.decimal", "12345678901234567890.123400", True, "12345678901234567890.1234"),
    ("test.boolean", True, True, True), ("test.int8", "128", False, None),
    ("test.int8", 8, False, None), ("test.string", None, False, None),
    ("test.string", "", True, ""),
]:
    status, result = validate(identity, value)
    assert status == 200 and result["valid"] is valid, result
    if valid:
        assert result["normalizedValue"] == normalized
    else:
        assert "normalizedValue" not in result and result["issues"]
assert validate("missing", "x")[0] == 404
# Raw bodies exercise JSON decoding itself, not Python's string normalization.
path = "/demo/dictionary/elements/test.string/validate"
for body in [
    b'', b'{', b'{"value":}', b'{"value":"x"} trailing',
    b'{"value":"\\ud800"}', b'{"value":"\\udc00"}', b'{"value":"\\ud800x"}',
    b'{"value":"\\ud800\\ud800"}', b'{"\\ud800":"x"}',
    b'{"value":["\\udc00"]}', b'{"value":{"\\ud800":"x"}}',
    b'{"value":"\xff"}',
]:
    status, _, text = request(path, body)
    assert status == 400 and json.loads(text) == {
        "valid": False, "issues": [{"code": "DEMO_INVALID_JSON"}]}, (body, status, text)
for body in [b'{}', b'{"value":"x","value":"y"}', b'{"value":"x","identity":"admin"}']:
    status, _, text = request(path, body)
    assert status == 400 and json.loads(text) == {
        "valid": False, "issues": [{"code": "DEMO_INPUT_SHAPE"}]}, (body, status, text)
for body, expected in [
    (b'{"value":"\\ud83d\\ude00"}', "\U0001f600"),
    ('{"value":"España 😀"}'.encode(), "España 😀"),
    (b'{"value":"literal \\\\ud800"}', r"literal \ud800"),
]:
    status, _, text = request(path, body)
    assert status == 200 and json.loads(text) == {
        "valid": True, "normalizedValue": expected, "issues": []}, (body, status, text)
assert request("/demo/dictionary/elements/test.string/validate", b'"' + b'x' * 20000 + b'"')[0] == 413
assert request("/demo/dictionary/elements/test.string/validate", b'x', content_type="text/plain")[0] == 415
assert request("/missing")[0] == 404
status, _, text = request("/demo/info", b'{}', method="POST")
assert status == 405 and json.loads(text)["issues"]
status, headers, _ = request("/demo/info", origin="https://untrusted.invalid")
assert status == 403 and headers.get("Access-Control-Allow-Origin") is None
req = Request(BASE + "/demo/dictionary/elements/test.int8/validate", method="OPTIONS", headers={
    "Origin": ORIGIN, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
with urlopen(req) as response:
    assert response.status == 204 and response.headers["Access-Control-Allow-Origin"] == ORIGIN
print("PASS: real HTTP host, 18 elements, 17 original type vectors, exact numbers, "
      "Unicode/JSON regressions, errors, limits and restricted CORS")