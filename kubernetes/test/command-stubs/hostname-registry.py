"""Small API store for testing create/replace contention across real processes.

The lock models API-server atomic writes, not a lock available to the scripts.
Unlike a canned-success kubectl stub, this rejects stale resourceVersions and
duplicate creates, and preserves reservations across separate lifecycle calls.
"""
import fcntl
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
operation = next(word for word in args if word in ("get", "create", "replace"))
manifest = json.load(sys.stdin) if operation != "get" else None
state_path = os.environ.get("OR_STACK_TEST_REGISTRY_FILE")
if os.environ.get("OR_STACK_TEST_REGISTRY_ERROR"):
    print("Error from server (Forbidden): hostname registry access denied", file=sys.stderr)
    sys.exit(1)
if not state_path:
    sys.exit(0)

path = Path(state_path)
with Path(state_path + ".lock").open("w") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    existing = json.loads(path.read_text()) if path.exists() else None
    if operation == "get":
        if existing:
            print(json.dumps(existing))
        sys.exit(0)
    if operation == "create" and existing:
        print("Error from server (AlreadyExists): configmap already exists", file=sys.stderr)
        sys.exit(1)
    if operation == "replace" and (
        not existing or manifest["metadata"]["resourceVersion"] != existing["metadata"]["resourceVersion"]
    ):
        print("Error from server (Conflict): object has been modified", file=sys.stderr)
        sys.exit(1)
    version = int(existing["metadata"]["resourceVersion"]) + 1 if existing else 1
    manifest["metadata"]["resourceVersion"] = str(version)
    path.write_text(json.dumps(manifest))
