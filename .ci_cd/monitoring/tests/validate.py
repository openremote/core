#!/usr/bin/env python3
"""Render and validate the base and optional trace configuration without running Alloy."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    plugin = subprocess.run(["docker", "compose", "version"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    compose = ["docker", "compose"] if plugin.returncode == 0 else ["docker-compose"]
    # Prevent an operator's exported deployment settings from overriding test inputs.
    environment = os.environ.copy()
    for line in (ROOT / "customer.env.example").read_text().splitlines():
        entry = line.removeprefix("# ")
        if "=" in entry:
            environment.pop(entry.split("=", 1)[0], None)
    with tempfile.TemporaryDirectory(prefix="alloy-config-check-") as temporary:
        directory = Path(temporary).resolve()
        for name in ("loki_password", "metrics_password"):
            path = directory / name
            path.write_text("validation-only")
            path.chmod(0o444)
        environment.update(LOKI_PASSWORD_FILE=str(directory / "loki_password"),
                           METRICS_PASSWORD_FILE=str(directory / "metrics_password"))
        for traces in (False, True):
            mode = "traces" if traces else "base"
            command = compose + ["--env-file", str(ROOT / "customer.env.example"),
                                 "-f", str(ROOT / "compose.yaml")]
            if traces:
                environment.update(OTLP_GRPC_LISTEN_ADDRESS="127.0.0.1:4317",
                                   OTLP_HTTP_LISTEN_ADDRESS="127.0.0.1:4318",
                                   TEMPO_OTLP_ENDPOINT="https://tempo.example.com")
                command += ["-f", str(ROOT / "compose.traces.yaml")]
            model = json.loads(subprocess.check_output(
                command + ["config", "--format", "json"], env=environment, text=True))
            assert set(model["services"]) == {"alloy"}, "Expected one collector"
            service = model["services"]["alloy"]
            assert service["network_mode"] == "host" and not service.get("networks")
            expected = {"logs.alloy", "host-metrics.alloy"}
            if traces:
                expected.add("traces.alloy")
                assert "openremote.customer=customer" in service["environment"]["OTEL_RESOURCE_ATTRIBUTES"]
            else:
                assert not any(key.startswith(("OTLP_", "TEMPO_", "OTEL_"))
                               for key in service["environment"])
            mounts = [mount for mount in service["volumes"]
                      if mount["target"].startswith("/etc/alloy/config/")]
            assert {Path(m["target"]).name for m in mounts} == expected
            assert len(mounts) == len(expected)
            assert model["volumes"]["alloy-data"]["external"] is True
            assert model["volumes"]["alloy-data"]["name"] == "alloy-customer-staging-data"
            print(f"PASS {mode} Compose selection", flush=True)
            run = ["docker", "run", "--rm", "--pull", "never", "--network", "none",
                   "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges=true",
                   "--memory", "512m", "--cpus", "0.5"]
            for key, value in service["environment"].items():
                run += ["--env", f"{key}={value}"]
            for mount in mounts:
                source = Path(mount["source"])
                assert source.resolve() == ROOT / "config" / Path(mount["target"]).name
                assert mount["type"] == "bind" and mount["read_only"] is True
                run += ["--mount", f"type=bind,src={source},dst={mount['target']},readonly"]
            for name in ("loki_password", "metrics_password"):
                run += ["--mount", f"type=bind,src={directory / name},dst=/run/secrets/{name},readonly"]
            subprocess.run(run + [service["image"], "validate", "/etc/alloy/config"], check=True)
            print(f"PASS {mode} Alloy configuration", flush=True)


if __name__ == "__main__":
    main()
