"""Compare real demo hosts without coercing numeric descriptor values to float.

Requires all three running hosts and original contract fixtures; no mocks or private library APIs.
"""
import argparse
import json
import os
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
for runtime in ("dotnet", "java", "php"):
    parser.add_argument("--" + runtime + "-url", required=True)
args = parser.parse_args()
urls = {runtime: getattr(args, runtime + "_url").rstrip("/") for runtime in ("dotnet", "java", "php")}
origin = os.environ.get("ARP_DEMO_ORIGIN", "http://127.0.0.1:4173")
root = Path(os.environ["ARP_CONTRACT_PATH"])
count = 0


def request(runtime, path, body=None, method=None, supplied_origin=origin, media="application/json", extra=None):
    headers = {"Content-Type": media}
    if supplied_origin is not None:
        headers["Origin"] = supplied_origin
    headers.update(extra or {})
    req = Request(urls[runtime] + path, data=body, headers=headers, method=method)
    try:
        response = urlopen(req, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        result = json.loads(raw, parse_float=Decimal, parse_int=Decimal) if raw else None
        cors = response.headers.get("Access-Control-Allow-Origin")
        assert response.headers.get("Access-Control-Allow-Credentials") is None
        return response.status, result, cors


def canonical(value):
    # Python otherwise equates True with 1; preserve JSON kinds as well as exact numeric values.
    if value is None:
        return ("null",)
    if isinstance(value, bool):
        return ("boolean", value)
    if isinstance(value, (Decimal, int)):
        return ("number", value)
    if isinstance(value, str):
        return ("string", value)
    if isinstance(value, list):
        return ("array", tuple(canonical(item) for item in value))
    return ("object", tuple(sorted((key, canonical(item)) for key, item in value.items())))


assert canonical(True) != canonical(Decimal(1))
assert canonical(Decimal("1.0")) == canonical(Decimal("1"))


def compare(path, **options):
    global count
    results = {runtime: request(runtime, path, **options) for runtime in urls}
    if path == "/demo/info":
        results = {runtime: (status, {key: value for key, value in result.items()
                   if key not in ("runtime", "demoProtocol")} if status == 200 else result, cors)
                   for runtime, (status, result, cors) in results.items()}
    reference = results["dotnet"]
    expected = (reference[0], canonical(reference[1]), reference[2])
    assert all((status, canonical(body), cors) == expected
               for status, body, cors in results.values()), (path, options, results)
    count += 1
    return reference


infos = {}
for runtime in urls:
    status, info, cors = request(runtime, "/demo/info")
    assert status == 200 and info["runtime"] == runtime
    assert info["demoProtocol"] == "arp-demo-" + runtime + "/1" and cors == origin
    infos[runtime] = {key: value for key, value in info.items() if key not in ("runtime", "demoProtocol")}
assert infos["dotnet"] == infos["java"] == infos["php"]
status, listing, _ = compare("/demo/dictionary/elements")
assert status == 200 and len(listing["elements"]) == 18
for element in listing["elements"]:
    identity = element["id"]
    compare("/demo/dictionary/elements/" + identity)
    for value in ["", "x", None, True, False, 0, 1, [], {}, "9223372036854775807", "1.2300", "é😀", "\u0000"]:
        compare("/demo/dictionary/elements/" + identity + "/validate",
                body=json.dumps({"value": value}).encode())
vectors = json.loads((root / "fixtures/dictionary/v1/values.json").read_text())
for vector in (vector for vector in vectors if vector["id"].startswith("type-")):
    status, result, _ = compare("/demo/dictionary/elements/" + vector["element"]["id"] + "/validate",
                              body=json.dumps({"value": vector["value"]}).encode())
    assert status == 200 and result == vector["expected"]
path = "/demo/dictionary/elements/test.string/validate"
for body in [
    b'{', b'', b'{}', b'null', b'[]', b'1', b'{"value":}', b'{"value":"x"} trailing',
    b'{"value":"x","value":"y"}', b'{"value":"x","va\\u006cue":"y"}',
    b'{"value":{"a":1,"a":2}}', b'{"value":"\\ud800"}', b'{"value":"\\udc00"}',
    b'{"\\ud800":"x"}', b'{"value":["\\ud800"]}', b'{"value":"\xff"}',
    b'{"value":"\xc0\xaf"}', b'{"value":"\xed\xa0\x80"}',
    b'{"value":"\\ud83d\\ude00"}', b'{"value":"literal \\\\ud800"}',
    b'{"va\\u006cue":"x"}', b'{"value":1e127}', b'{"value":9223372036854775807}',
    b'{"value":' + b'[' * 63 + b'0' + b']' * 63 + b'}',
    b'{"value":' + b'[' * 64 + b'0' + b']' * 64 + b'}',
    b'{"value":"' + b'x' * (16384 - len(b'{"value":""}')) + b'"}',
    b'{"value":"' + b'x' * 16384 + b'"}',
]:
    compare(path, body=body)
compare(path, body=b'x', media="text/plain")
compare(path, body=b'{"value":"x"}', media="application/problem+json")
compare(path, body=b'{}', method="GET")
compare("/missing")
compare("/demo/info", body=b'{}', method="POST")
compare("/demo/dictionary/elements/missing")
compare("/demo/dictionary/elements/missing/validate", body=b'{"value":"x"}')
compare("/demo/info", supplied_origin="https://untrusted.invalid")
compare("/demo/info", supplied_origin=None)
for supplied_origin in (origin, "https://untrusted.invalid"):
    compare(path, method="OPTIONS", supplied_origin=supplied_origin, extra={
        "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
print(f"PASS: {count} equivalent exchanges per host, plus truthful runtime info; exact JSON numeric comparisons")