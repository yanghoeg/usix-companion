"""Strict shared contract handling with packaged, local-only schema resolution."""
import hashlib
import json
from datetime import datetime
from importlib.resources import files
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


def _reject(value):
    raise ValueError("Floating point and non-finite JSON numbers are not supported")


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _check(value, depth=0):
    if depth > 32:
        raise ValueError("JSON nesting exceeds 32")
    if value is None or type(value) is bool:
        return
    if type(value) is int:
        if abs(value) > 9007199254740991:
            raise ValueError("Integer outside interoperable range")
    elif isinstance(value, str):
        value.encode("utf-8", errors="strict")
    elif isinstance(value, list):
        for item in value:
            _check(item, depth + 1)
    elif isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("Object keys must be strings")
            _check(key, depth + 1)
            _check(item, depth + 1)
    else:
        raise ValueError("Unsupported JSON value")


def loads(raw, max_bytes=65536):
    if isinstance(raw, str):
        raw = raw.encode("utf-8", errors="strict")
    if len(raw) > max_bytes:
        raise ValueError("JSON exceeds the negotiated limit")
    result = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_pairs,
                        parse_float=_reject, parse_constant=_reject)
    _check(result)
    return result


def dumps(value):
    _check(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def payload_hash(command):
    return "sha256:" + hashlib.sha256(dumps({key: command[key] for key in ("operation", "scope", "payload")})).hexdigest()


class Contracts:
    def __init__(self):
        schemas = [loads(path.read_bytes()) for path in files("usix_companion").joinpath("contracts").iterdir()
                   if path.name.endswith(".schema.json")]
        if len(schemas) != 9:
            raise ValueError("Packaged shared contracts are missing")
        registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
        self.validators = {s["$id"].rsplit(":", 1)[1]: Draft202012Validator(s, registry=registry, format_checker=FormatChecker()) for s in schemas}

    def validate(self, name, value):
        _check(value)
        self.validators[name].validate(value)
        if name == "command" and value["payloadHash"] != payload_hash(value):
            raise ValueError("Payload digest mismatch")


def utc(millis):
    return datetime.fromtimestamp(millis / 1000, tz=__import__("datetime").timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
