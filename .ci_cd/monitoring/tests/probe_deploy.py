#!/usr/bin/env python3
"""Opt-in Linux/root Docker lifecycle check. Uses dummy credentials and disposable state."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid

SOURCE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("deployment", SOURCE / "deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

# Exercise the real CLI/lock and deployment functions in a child. Only AWS secret
# retrieval is replaced; it already has a separate real instance-role acceptance check.
CHILD = r'''import importlib.util, json, sys
from pathlib import Path
path, secret = Path(sys.argv.pop(1)), Path(sys.argv.pop(1))
spec = importlib.util.spec_from_file_location("deployment", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
def dummy_secret(arn, account):
    assert arn == "fixture-only" and account == "000000000000"
    return {k: v.encode() for k, v in json.loads(secret.read_text()).items()}
module.read_secret = dummy_secret
real_run = module.run
def offline_run(command, seconds=30):
    if command[0] == "aws" or command[:2] == ["docker", "pull"]:
        raise module.Error("Probe must not contact AWS or pull images")
    return real_run(command, seconds)
module.run = offline_run
sys.exit(module.main())
'''


def check(condition, message):
    if not condition:
        raise deploy.Error(message)


def snapshot():
    ids = deploy.docker("ps", "-aq", "--no-trunc").split()
    if not ids:
        return []
    return sorted(deploy.docker("inspect", "--format", "{{.Id}} {{.State.StartedAt}} {{.RestartCount}}", *ids).splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-uuid", required=True, help="Independently verified retained data filesystem UUID")
    args = parser.parse_args()
    check(sys.platform == "linux" and os.geteuid() == 0, "Run as root on the staging Linux host")
    os.umask(0o077)
    deploy.check_mount(args.data_uuid)
    memory = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
    check(int(memory["MemAvailable"].split()[0]) >= 1024 * 1024, "Need at least 1 GiB MemAvailable before this test")
    check(shutil.disk_usage("/tmp").free >= 1024**3 and shutil.disk_usage(deploy.DATA).free >= 256 * 1024**2,
          "Need 1 GiB free on the temporary filesystem and 256 MiB on Docker data")
    image = json.loads(deploy.docker("image", "inspect", "grafana/alloy:v1.18.0"))[0]
    digests = [d for d in image.get("RepoDigests", []) if d.startswith("grafana/alloy@sha256:")]
    check(len(digests) == 1, "Need the cached grafana/alloy:v1.18.0 image with one repository digest; no pull performed")
    before = snapshot()
    project = "or-deploy-check-" + uuid.uuid4().hex[:12]
    volume = project + "-data"
    check(not deploy.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={project}").strip(), "Fixture project already exists")
    check(volume not in deploy.docker("volume", "ls", "--format", "{{.Name}}").splitlines(), "Fixture volume already exists")
    root = Path(tempfile.mkdtemp(prefix=project + "-", dir="/tmp")).resolve()
    print(f"Disposable project: {project}\nDisposable files: {root}", flush=True)
    install = root / "installation"
    rebuilt = root / "fresh-boot"
    source = root / "source"
    source.mkdir()
    env_file = root / "customer.env"
    secret = root / "dummy-secret.json"
    backend = socket.socket()
    backend.bind(("127.0.0.1", 0))  # Reserve a non-listening loopback destination for dummy telemetry.
    port_reservations = []
    for unused in range(3):
        reservation = socket.socket()
        reservation.bind(("127.0.0.1", 0))
        port_reservations.append(reservation)
    ports = [s.getsockname()[1] for s in port_reservations]
    success = False

    def invoke(action, *, traces=False, create=False, installation=None, expect=0, data_uuid=None, error_contains=None):
        command = [sys.executable, "-E", "-s", "-B", "-c", CHILD, str(SOURCE / "deploy.py"), str(secret),
                   action, "--project", project, "--root", str(installation or install),
                   "--data-uuid", data_uuid or args.data_uuid]
        if action in ("deploy", "plan"):
            command += ["--source", str(source), "--env-file", str(env_file), "--secret-arn", "fixture-only"]
            if traces:
                command.append("--traces")
            if create:
                command.append("--create-state")
        print(f"Running {action}" + (" with traces" if traces else ""), flush=True)
        result = subprocess.run(command, env=deploy.ENV, text=True, capture_output=True, timeout=420)
        (root / "last-command.log").write_text(result.stdout + result.stderr)
        if result.returncode != expect:
            print(result.stdout + result.stderr, flush=True)
            raise deploy.Error(f"{action}: expected exit {expect}, got {result.returncode}; inspect last-command.log")
        if error_contains:
            check(error_contains in result.stdout + result.stderr, f"{action}: unexpected failure; inspect last-command.log")
        return result.stdout

    def collector(installation=None):
        record = deploy.containers(project, (installation or install) / project)
        check(record is not None, "Fixture collector missing")
        return record

    def identity():
        record = collector()
        return record["Id"], record["State"]["StartedAt"], record["RestartCount"]

    def marker(installation=None):
        record = collector(installation)
        check(deploy.docker("exec", record["Id"], "cat", "/var/lib/alloy/data/.deployment-check") == "retained-marker",
              "Fixture state marker was not preserved")

    try:
        contents = deploy.files(SOURCE, SOURCE / "customer.env.example", True)
        contents.pop("customer.env")
        contents["compose.yaml"] = contents["compose.yaml"].replace(b"      - run\n", b"      - run\n      - --disable-reporting\n", 1)
        contents["image.override.yaml"] = json.dumps({"services": {"alloy": {"image": digests[0]}}}).encode()
        deploy.write_files(source, contents)
        settings = dict(line.split("=", 1) for line in (SOURCE / "customer.env.example").read_text().splitlines()
                        if line and not line.startswith("#"))
        endpoint = f"https://127.0.0.1:{backend.getsockname()[1]}"
        settings.update(MONITORING_COMPOSE_PROJECT=project, ALLOY_DATA_VOLUME=volume, ALLOY_HTTP_PORT=str(ports[0]),
                        ALLOY_CUSTOMER="deployment-check", ALLOY_ENVIRONMENT="fixture", ALLOY_HOST=project,
                        ALLOY_AWS_ACCOUNT_ID="000000000000", HOST_METRICS_INSTANCE=project,
                        HOST_METRICS_JOB=project + "-node", ALLOY_METRICS_JOB=project + "-alloy",
                        SOURCE_COMPOSE_PROJECT=project + "-no-log-source", SOURCE_COMPOSE_SERVICES_REGEX="no-service",
                        LOKI_URL=endpoint + "/loki/api/v1/push", METRICS_URL=endpoint + "/api/v1/write",
                        LOKI_USERNAME="fixture", METRICS_USERNAME="fixture", TEMPO_OTLP_ENDPOINT=endpoint,
                        OTLP_GRPC_LISTEN_ADDRESS=f"127.0.0.1:{ports[1]}", OTLP_HTTP_LISTEN_ADDRESS=f"127.0.0.1:{ports[2]}")
        env_file.write_text("".join(f"{key}={value}\n" for key, value in settings.items()))
        secret.write_text(json.dumps({"loki_password": "fixture-only-loki", "metrics_password": "fixture-only-metrics"}))
        for reservation in port_reservations:
            reservation.close()
        check(not deploy.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={settings['SOURCE_COMPOSE_PROJECT']}").strip(),
              "Unexpected fixture log source exists")
        invoke("plan", create=True)
        check(not (install / project).exists(), "Plan created installed configuration")
        check(volume not in deploy.docker("volume", "ls", "--format", "{{.Name}}").splitlines(), "Plan created state")
        print("PASS plan leaves installed configuration and state absent", flush=True)

        invoke("deploy", create=True)
        first = deploy.pointer(install / project, "current")
        original = identity()
        current = collector()
        check(current["Image"] == image["Id"], "Unexpected fixture image")
        check(current["HostConfig"]["Memory"] == 512 * 1024**2, "Unexpected memory limit")
        for name in ("loki_password", "metrics_password"):
            check((first / "credentials" / name).stat().st_mode & 0o777 == 0o600, "Credential file mode mismatch")
        deploy.docker("exec", current["Id"], "/bin/sh", "-ec", "printf retained-marker > /var/lib/alloy/data/.deployment-check")
        print("PASS fresh install is healthy with private dummy credentials and writable state", flush=True)

        invoke("deploy")
        check(identity() == original, "Unchanged rerun recreated or restarted fixture")
        check(deploy.pointer(install / project, "previous") is None, "Rerun changed previous release")
        marker()
        print("PASS unchanged rerun preserves collector identity and state", flush=True)

        deploy.docker("stop", "--time", "10", collector()["Id"])
        invoke("deploy")
        check(collector()["Id"] == original[0], "Stopped collector was unnecessarily recreated")
        marker()
        deploy.docker("rm", "-f", collector()["Id"])
        invoke("deploy")
        check(collector()["Id"] != original[0], "Missing collector was not recreated")
        marker()
        print("PASS stopped/missing collector is restored with retained state", flush=True)

        secret.write_text(json.dumps({"loki_password": "fixture-only-rotated", "metrics_password": "fixture-only-rotated"}))
        prior = identity()
        invoke("deploy", traces=True)
        check(identity()[0] != prior[0] and deploy.pointer(install / project, "previous") == first, "Update did not retain the previous release")
        check((deploy.pointer(install / project, "current") / "compose.traces.yaml").exists(), "Trace override not retained")
        # A successful empty OTLP request verifies the disposable HTTP receiver, not central delivery.
        request = deploy.urllib.request.Request(f"http://127.0.0.1:{ports[2]}/v1/traces", data=b"", headers={"Content-Type": "application/x-protobuf"})
        deadline = time.monotonic() + 20
        while True:
            try:
                with deploy.HTTP.open(request, timeout=2) as response:
                    check(response.status == 200, "Fixture OTLP HTTP receiver did not accept a request")
                break
            except OSError:
                check(time.monotonic() < deadline, "Fixture OTLP HTTP receiver did not become ready")
                time.sleep(0.5)
        marker()
        print("PASS config/credential update enables isolated trace receiver and retains previous release", flush=True)

        invoke("rollback")
        check(deploy.pointer(install / project, "current") == first, "Rollback did not select original release")
        check((first / "credentials/loki_password").read_text() == "fixture-only-loki", "Rollback lost original dummy credentials")
        check(not deploy.listeners(ports[1]) and not deploy.listeners(ports[2]), "Trace listeners remain after rollback")
        marker()
        print("PASS explicit rollback restores old configuration and credentials with the same state", flush=True)

        prior = identity()
        original_config = (source / "config/logs.alloy").read_bytes()
        (source / "config/logs.alloy").write_bytes(original_config + b"\nthis is invalid alloy syntax!\n")
        invoke("deploy", expect=1, error_contains="docker run --rm failed")
        (source / "config/logs.alloy").write_bytes(original_config)
        invoke("plan", expect=1, data_uuid="00000000-0000-0000-0000-000000000000",
               error_contains="Expected writable XFS data filesystem")
        check(identity() == prior and deploy.pointer(install / project, "current") == first, "Rejected input changed the running fixture")
        print("PASS invalid Alloy configuration and wrong data UUID leave the collector unchanged", flush=True)

        deploy.docker("rm", "-f", collector()["Id"])
        invoke("deploy", installation=rebuilt)
        marker(rebuilt)
        check(deploy.pointer(rebuilt / project, "current") is not None, "Fresh host configuration was not rebuilt")
        print("PASS fresh configuration directory reuses retained labeled state without initialization", flush=True)
        success = True
    finally:
        for reservation in port_reservations:
            reservation.close()
        backend.close()
        # Delete only known disposable objects. Never prune or clean by a broad prefix.
        ids = deploy.docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={project}").split()
        for identifier in ids:
            record = json.loads(deploy.docker("inspect", identifier))[0]
            labels = record["Config"].get("Labels") or {}
            check(labels.get(deploy.LABEL) == project and labels.get(deploy.ROOT_LABEL) in (str(install / project), str(rebuilt / project)),
                  "Cleanup stopped: unexpected fixture ownership; retain printed project/files")
            if not success:
                logs = subprocess.run(["docker", "logs", "--tail", "80", identifier], env=deploy.ENV,
                                      text=True, capture_output=True, timeout=15)
                (root / "collector.log").write_text(logs.stdout + logs.stderr)
            deploy.docker("rm", "-f", identifier)
        if volume in deploy.docker("volume", "ls", "--format", "{{.Name}}").splitlines():
            check(deploy.volume(volume, project), "Cleanup stopped: unexpected state volume")
            deploy.docker("volume", "rm", volume)
        if snapshot() != before:
            success = False
            raise deploy.Error("Other container lifecycle changed during test; investigate before proceeding")
        if success:
            shutil.rmtree(root)
            print("PASS disposable resources removed; original container lifecycle unchanged", flush=True)
        else:
            print(f"Disposable containers/volume removed; failure files retained at {root}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
