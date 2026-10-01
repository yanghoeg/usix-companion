#!/usr/bin/env python3
"""Trusted local setup: load private WSS/owner credentials; print no secrets."""
import argparse
import json
from pathlib import Path
import sys

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone-config", type=Path, required=True)
    parser.add_argument("--owner-token-file", type=Path, required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8760")
    parser.add_argument("--disable", action="store_true")
    args = parser.parse_args()
    from usix_companion.cli import owner_token, packet
    from usix_companion.client import call, private_json
    data = private_json(args.phone_config)
    fields = {key: None if args.disable else data[key] for key in ("url", "deviceBearer", "trustedCertificatePem")}
    result = call({"transport": "loopback", "endpoint": args.endpoint}, "/v2/admin/remote", owner_token(args.owner_token_file), packet(**fields))
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result.get("kind") == "remote_configured" else 1

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, KeyError):
        print('{"error":"Invalid local remote setup; credentials omitted"}')
        raise SystemExit(1)
