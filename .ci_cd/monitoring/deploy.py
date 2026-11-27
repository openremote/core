#!/usr/bin/env python3
"""Operate one standalone Alloy project using ordinary Compose release directories."""
import argparse
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

LABEL = "org.openremote.monitoring.project"
ROOT_LABEL = "org.openremote.monitoring.root"
DATA = Path("/var/lib/docker/volumes")
ENV = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}
ENV.update(DOCKER_HOST="unix:///var/run/docker.sock", AWS_CONFIG_FILE="/dev/null",
           AWS_SHARED_CREDENTIALS_FILE="/dev/null", AWS_EC2_METADATA_DISABLED="false",
           AWS_PAGER="", AWS_MAX_ATTEMPTS="2", AWS_RETRY_MODE="standard")
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Error(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Error(message)


def run(command, seconds=30):
    try:
        result = subprocess.run(command, env=ENV, capture_output=True, text=True, timeout=seconds)
    except subprocess.TimeoutExpired:
        raise Error(f"{command[0]} timed out; inspect actual state before retrying") from None
    except OSError:
        raise Error(f"Cannot execute {command[0]}") from None
    # Never emit subprocess output: a failed secret fetch/validation may contain sensitive data.
    require(result.returncode == 0, f"{' '.join(command[:3])} failed (exit {result.returncode}); inspect this operation locally")
    return result.stdout


def docker(*args):
    return run(["docker", *args])


def compose_command():
    try:
        run(["docker", "compose", "version"])
        return ["docker", "compose"]
    except Error:
        run(["docker-compose", "version"])
        return ["docker-compose"]


def compose(directory, env_file, project):
    command = compose_command() + ["--project-directory", str(directory), "--env-file", str(env_file),
                                   "-p", project, "-f", str(directory / "compose.yaml")]
    for name in ("compose.traces.yaml", "image.override.yaml", "runtime.override.yaml"):
        if (directory / name).exists():
            command += ["-f", str(directory / name)]
    return command


def render(command):
    return json.loads(run(command + ["config", "--format", "json"]))


def check_mount(expected):
    mounted = json.loads(run(["findmnt", "--json", "-M", str(DATA), "-o", "TARGET,FSTYPE,UUID,OPTIONS"]))["filesystems"][0]
    require(mounted["target"] == str(DATA) and mounted["fstype"] == "xfs"
            and mounted["uuid"] == expected and "rw" in mounted["options"].split(","),
            "Expected writable XFS data filesystem is not mounted; no state will be created")
    boot = run(["findmnt", "-rn", "-M", "/", "-o", "UUID"]).strip()
    require(boot and boot != expected, "Docker state must be separate from the boot filesystem")
    require(docker("info", "--format", "{{.DockerRootDir}}").strip() == "/var/lib/docker",
            "This deployment expects DockerRootDir=/var/lib/docker")


def containers(project, root):
    ids = docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={project}").split()
    records = json.loads(docker("inspect", *ids)) if ids else []
    require(len(records) <= 1, "Multiple project containers; inspect manually")
    for record in records:
        labels = record["Config"].get("Labels") or {}
        require(labels.get(LABEL) == project and labels.get(ROOT_LABEL) == str(root)
                and labels.get("com.docker.compose.service") == "alloy",
                "Unmanaged project container; perform the agreed manual cutover first")
    return records[0] if records else None


def volume(name, project):
    require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]+", name), "Invalid stable volume name")
    if name not in docker("volume", "ls", "--format", "{{.Name}}").splitlines():
        return None
    record = json.loads(docker("volume", "inspect", name))[0]
    require((record.get("Labels") or {}).get(LABEL) == project, "State volume ownership mismatch; no automatic adoption")
    require(record["Driver"] == "local" and not record.get("Options")
            and record["Mountpoint"] == str(DATA / name / "_data"), "Unexpected state volume storage")
    return record


def listeners(port, pid=0):
    """Return listener socket owners, including wildcard/IPv6 collisions, without a host inventory."""
    owned = set()
    if pid:
        for fd in Path(f"/proc/{pid}/fd").glob("*"):
            try:
                owned.add(os.readlink(fd))
            except FileNotFoundError:
                pass
    found = []
    for name in ("tcp", "tcp6"):
        for line in Path("/proc/net", name).read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == "0A" and int(fields[1].split(":")[1], 16) == port:
                found.append(f"socket:[{fields[9]}]" in owned)
    return found


def check_settings(model, project):
    require(set(model["services"]) == {"alloy"}, "Only the alloy service is supported")
    service = model["services"]["alloy"]
    require(service.get("network_mode") == "host", "Collector must use the host network")
    settings = service["environment"]
    require(not re.fullmatch(settings["SOURCE_COMPOSE_PROJECT"], project), "Log selection must exclude the monitoring project")
    for key in ("LOKI_URL", "METRICS_URL", "TEMPO_OTLP_ENDPOINT"):
        if key in settings:
            url = urllib.parse.urlsplit(settings[key])
            require(url.scheme == "https" and url.hostname and not url.username and not url.password,
                    f"{key} must be HTTPS without embedded credentials")
    command = service["command"]
    addresses = [arg.split("=", 1)[1] for arg in command if arg.startswith("--server.http.listen-addr=")]
    require(len(addresses) == 1 and addresses[0].startswith("127.0.0.1:"), "Management must listen on loopback")
    ports = [int(addresses[0].rsplit(":", 1)[1])]
    private_networks = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")]
    assigned = None
    for key in ("OTLP_GRPC_LISTEN_ADDRESS", "OTLP_HTTP_LISTEN_ADDRESS"):
        if key not in settings:
            continue
        address, port = settings[key].rsplit(":", 1)
        ip = ipaddress.ip_address(address.strip("[]"))
        require(ip.is_loopback or any(ip in network for network in private_networks), "OTLP requires a loopback or private host IP")
        if assigned is None:
            assigned = {a["local"] for interface in json.loads(run(["ip", "-j", "address", "show"])) for a in interface.get("addr_info", [])}
        require(str(ip) in assigned, "OTLP address is not assigned to this host")
        ports.append(int(port))
    require(len(ports) == len(set(ports)) and all(1024 <= p <= 65535 for p in ports), "Invalid or duplicate listener ports")
    require(model["volumes"]["alloy-data"].get("external") is True, "State volume must be external")
    return model["volumes"]["alloy-data"]["name"], ports


def check_ports(ports, record):
    pid = record["State"]["Pid"] if record and record["State"]["Running"] else 0
    for port in ports:
        require(all(listeners(port, pid)), f"Port {port} belongs to another process")


def read_secret(arn, account):
    match = re.fullmatch(r"arn:aws:secretsmanager:([a-z0-9-]+):([0-9]{12}):secret:.+", arn)
    require(match and match[2] == account, "Secret ARN must match the configured AWS account")
    base = ["aws", "--region", match[1], "--output", "json", "--cli-connect-timeout", "5", "--cli-read-timeout", "15"]
    caller = json.loads(run(base + ["sts", "get-caller-identity"]))
    require(caller["Account"] == account and ":assumed-role/" in caller["Arn"], "Expected same-account instance credentials")
    response = json.loads(run(base + ["secretsmanager", "get-secret-value", "--secret-id", arn, "--version-stage", "AWSCURRENT"], 60))
    require(response.get("ARN") == arn and "AWSCURRENT" in response.get("VersionStages", []), "Unexpected secret response")
    value = json.loads(response.get("SecretString", "null"))
    require(isinstance(value, dict), "Secret must be a JSON object")
    required = ("loki_password", "metrics_password")
    require(all(isinstance(value.get(key), str) and value[key].strip() for key in required), "Secret requires two non-empty password strings")
    return {key: value[key].encode() for key in required}


def files(source, env_file, traces):
    names = ["compose.yaml", "config/logs.alloy", "config/host-metrics.alloy"]
    if traces:
        names += ["compose.traces.yaml", "config/traces.alloy"]
    if (source / "image.override.yaml").exists():
        names += ["image.override.yaml"]
    result = {name: (source / name).read_bytes() for name in names}
    result["customer.env"] = env_file.read_bytes()
    return result


def write_files(directory, contents):
    for name, content in contents.items():
        target = directory / name
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        target.write_bytes(content)
        target.chmod(0o600)


def same_files(directory, contents):
    return directory is not None and all((directory / name).is_file() and (directory / name).read_bytes() == value
                                        for name, value in contents.items()) and (
        (directory / "compose.traces.yaml").exists() == ("compose.traces.yaml" in contents))


def pointer(root, name):
    path = root / name
    if not path.is_symlink():
        require(not path.exists(), f"{name} must be a release symlink")
        return None
    resolved = path.resolve(strict=True)
    require(resolved.parent == root / "releases", "Release pointer is outside this installation")
    return resolved


def publish(root, release):
    current = pointer(root, "current")
    if current == release:
        return
    for name, target in (("previous", current), ("current", release)):
        if target and target != pointer(root, name):
            temporary = root / (name + ".new")
            temporary.unlink(missing_ok=True)
            temporary.symlink_to(target.relative_to(root))
            temporary.replace(root / name)


def pin_image(image):
    require(re.fullmatch(r"grafana/alloy:(?!latest$)[A-Za-z0-9_.-]+|grafana/alloy@sha256:[0-9a-f]{64}", image), "Select an explicit Alloy version or resolved digest")
    if "@" in image:
        try:
            cached = json.loads(docker("image", "inspect", image))[0]
            if image in cached.get("RepoDigests", []):
                return image
        except Error:
            pass  # A missing digest may be fetched; never substitute its old tag.
    run(["docker", "pull", image], 180)
    digests = json.loads(docker("image", "inspect", image))[0].get("RepoDigests", [])
    matches = [d for d in digests if re.fullmatch(r"grafana/alloy@sha256:[0-9a-f]{64}", d)]
    require(len(matches) == 1 and ("@" not in image or matches[0] == image), "Cannot resolve an unambiguous Alloy image digest")
    return matches[0]


def validate_alloy(release, model):
    service = model["services"]["alloy"]
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
               "--user", "0:0", "--group-add", "473", "--cap-drop", "ALL",
               "--security-opt", "no-new-privileges=true", "--security-opt", "label=disable",
               "--memory", "512m", "--cpus", "0.5"]
    for key, value in service["environment"].items():
        command += ["--env", f"{key}={value}"]
    for mount in service["volumes"]:
        if mount["target"].startswith("/etc/alloy/config/"):
            command += ["--mount", f"type=bind,src={mount['source']},dst={mount['target']},readonly"]
    for name in ("loki_password", "metrics_password"):
        command += ["--mount", f"type=bind,src={release / 'credentials' / name},dst=/run/secrets/{name},readonly"]
    run(command + [service["image"], "validate", "/etc/alloy/config"], 90)


def state_access(name, image, initialize=False):
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
               "--user", "0:0", "--group-add", "473", "--cap-drop", "ALL", "--security-opt", "label=disable",
               "--memory", "64m", "--entrypoint", "/bin/sh"]
    if initialize:
        command += ["--cap-add", "CHOWN", "--cap-add", "FOWNER"]
    command += ["--mount", f"type=volume,src={name},dst=/var/lib/alloy/data", image, "-ec"]
    script = "f=$(mktemp /var/lib/alloy/data/.write-check.XXXXXX); rm -- \"$f\""
    if initialize:
        script = "chmod 0770 /var/lib/alloy/data; chown 473:473 /var/lib/alloy/data; " + script
    run(command + [script])


def health(port):
    try:
        for path in ("/-/ready", "/-/healthy"):
            with HTTP.open(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
                if response.status != 200:
                    return False
        return True
    except (OSError, TimeoutError):
        return False


def activate(root, release, project, data_uuid):
    command = compose(release, release / "customer.env", project)
    model = render(command)
    name, ports = check_settings(model, project)
    labels = model["services"]["alloy"].get("labels", {})
    require(labels.get(LABEL) == project and labels.get(ROOT_LABEL) == str(root), "Release ownership does not match this installation")
    check_mount(data_uuid)
    require(volume(name, project), "Expected state is missing; restore it or explicitly choose a fresh deployment")
    record = containers(project, root)
    check_ports(ports, record)
    state_access(name, model["services"]["alloy"]["image"])
    run(command + ["up", "-d", "--no-deps", "--no-build", "--pull", "never", "alloy"], 120)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        record = containers(project, root)
        if record and record["State"]["Running"]:
            owned = listeners(ports[0], record["State"]["Pid"])
            if owned and all(owned) and health(ports[0]):
                publish(root, release)
                print("PASS collector ready and components healthy; central delivery still requires verification")
                return
        time.sleep(2)
    raise Error(f"Collector did not become healthy. Retained attempted release: {release}. Inspect Compose logs and explicitly rollback if needed")


def operate(args):
    root = args.root.resolve() / args.project
    if args.action == "status":
        record = containers(args.project, root)
        ready = False
        if record and record["State"]["Running"]:
            for arg in record["Config"].get("Cmd", []):
                if arg.startswith("--server.http.listen-addr=127.0.0.1:"):
                    port = int(arg.rsplit(":", 1)[1])
                    owned = listeners(port, record["State"]["Pid"])
                    ready = bool(owned) and all(owned) and health(port)
        current, previous = pointer(root, "current"), pointer(root, "previous")
        print(json.dumps({"current": str(current) if current else None,
                          "previous": str(previous) if previous else None,
                          "local_health": "healthy" if ready else "not_ready", "central_delivery": "not_checked",
                          "container": record["Id"] if record else None,
                          "state": record["State"]["Status"] if record else "missing",
                          "image": record["Image"] if record else None}, indent=2))
        return
    require(args.data_uuid is not None, "--data-uuid is required")
    check_mount(args.data_uuid)
    record = containers(args.project, root)
    if args.action == "rollback":
        release = args.release.resolve(strict=True) if args.release else pointer(root, "previous")
        require(release and release.parent == root / "releases", "Select a retained release from this installation")
        model = render(compose(release, release / "customer.env", args.project))
        image = model["services"]["alloy"]["image"]
        require(re.fullmatch(r"grafana/alloy@sha256:[0-9a-f]{64}", image), "Rollback requires a retained image digest")
        pin_image(image)
        validate_alloy(release, model)
        activate(root, release, args.project, args.data_uuid)
        return
    require(args.env_file is not None, "--env-file is required")
    contents = files(args.source.resolve(), args.env_file.resolve(), args.traces)
    # A plan uses a temporary copy, never installed configuration or persistent state.
    with tempfile.TemporaryDirectory(prefix="alloy-plan-") as temporary:
        scratch = Path(temporary)
        write_files(scratch, contents)
        model = render(compose(scratch, scratch / "customer.env", args.project))
        name, ports = check_settings(model, args.project)
        check_ports(ports, record)
        existing_volume = volume(name, args.project)
        require(existing_volume or (args.create_state and not record and not pointer(root, "current")),
                "Expected state volume is missing; --create-state is only for an explicit fresh installation")
        current = pointer(root, "current")
        if current:
            old_model = render(compose(current, current / "customer.env", args.project))
            require(old_model["volumes"]["alloy-data"]["name"] == name, "Changing retained state requires operator review")
        if record:
            mounted = [m.get("Name") for m in record["Mounts"] if m["Destination"] == "/var/lib/alloy/data"]
            require(mounted == [name], "Changing the running collector's state volume requires operator review")
        if args.action == "plan":
            print(json.dumps({"project": args.project, "container": record["State"]["Status"] if record else "missing",
                              "state_volume": name, "create_state": not bool(existing_volume),
                              "image": model["services"]["alloy"]["image"], "traces": args.traces,
                              "root_free_bytes": shutil.disk_usage("/").free,
                              "data_free_bytes": shutil.disk_usage(DATA).free,
                              "next": "Deploy will resolve image and compare configuration/passwords before applying Compose"}, indent=2))
            return
    require(args.secret_arn is not None, "--secret-arn is required for deploy")
    credentials = read_secret(args.secret_arn, model["services"]["alloy"]["environment"]["ALLOY_AWS_ACCOUNT_ID"])
    image = pin_image(model["services"]["alloy"]["image"])
    contents["image.override.yaml"] = (json.dumps({"services": {"alloy": {"image": image}}}, indent=2) + "\n").encode()
    contents.update({"credentials/" + key: value for key, value in credentials.items()})
    current = pointer(root, "current")
    if same_files(current, contents):
        release = current
    else:
        release = root / "releases" / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8])
        release.mkdir(mode=0o700, parents=True)
        write_files(release, contents)
        override = {"services": {"alloy": {"labels": {LABEL: args.project, ROOT_LABEL: str(root)}}},
                    "secrets": {key: {"file": str(release / "credentials" / key)} for key in credentials}}
        write_files(release, {"runtime.override.yaml": (json.dumps(override, indent=2) + "\n").encode()})
    print(f"Selected release: {release}", flush=True)
    model = render(compose(release, release / "customer.env", args.project))
    validate_alloy(release, model)
    # Check again immediately before state creation/startup.
    check_mount(args.data_uuid)
    if not existing_volume:
        require(not volume(name, args.project), "State appeared during preparation; inspect before retrying")
        docker("volume", "create", "--label", f"{LABEL}={args.project}", name)
        state_access(name, image, initialize=True)
    activate(root, release, args.project, args.data_uuid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "deploy", "status", "rollback"))
    parser.add_argument("--project", required=True)
    parser.add_argument("--root", type=Path, default=Path("/opt/openremote/monitoring"))
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--data-uuid")
    parser.add_argument("--secret-arn")
    parser.add_argument("--traces", action="store_true")
    parser.add_argument("--create-state", action="store_true")
    parser.add_argument("--release", type=Path, help="Explicit retained rollback target; defaults to previous successful release")
    args = parser.parse_args()
    try:
        require(sys.platform == "linux" and os.geteuid() == 0, "Run as root on the Linux Docker host")
        require(re.fullmatch(r"[a-z0-9][a-z0-9_-]{1,62}", args.project), "Invalid independent Compose project name")
        require(args.root.is_absolute(), "Installation root must be absolute")
        if args.action in ("deploy", "rollback"):
            os.umask(0o077)
            root = args.root / args.project
            root.mkdir(mode=0o700, parents=True, exist_ok=True)
            require(not root.is_symlink() and root.stat().st_uid == 0 and root.stat().st_mode & 0o077 == 0,
                    "Installation directory must be root-owned and private")
            with (root / "deploy.lock").open("a") as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise Error("Another monitoring operation holds the deployment lock") from None
                operate(args)
        else:
            operate(args)
    except (Error, OSError, ValueError, KeyError, IndexError, TypeError, re.error):
        # Explicit errors are safe; malformed external data may include secret values.
        error = sys.exc_info()[1]
        print("FAIL " + (str(error) if isinstance(error, Error) else "Invalid input or host response; content withheld"), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
