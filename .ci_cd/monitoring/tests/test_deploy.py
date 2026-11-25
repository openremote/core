#!/usr/bin/env python3
"""Exercise deployment behavior with a fake Docker host; no AWS/Docker calls."""
import argparse
from contextlib import ExitStack, redirect_stdout
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("alloy_deploy", ROOT / "deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory())).resolve()
        self.source = self.directory / "source"
        self.source.mkdir()
        for name in ("compose.yaml", "compose.traces.yaml", "config/logs.alloy", "config/host-metrics.alloy", "config/traces.alloy"):
            target = self.source / name
            target.parent.mkdir(exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        self.env_file = self.directory / "customer.env"
        self.env_file.write_text((ROOT / "customer.env.example").read_text())
        self.args = argparse.Namespace(action="deploy", project="alloy-fixture", root=self.directory / "installation",
                                       source=self.source, env_file=self.env_file, traces=False, create_state=True,
                                       data_uuid="data-uuid", secret_arn="test-arn", release=None)
        self.root = self.args.root / self.args.project
        self.record = None
        self.volumes = {}
        self.calls = []
        self.starts = 0
        self.digest = "grafana/alloy@sha256:" + "a" * 64
        self.passwords = {"loki_password": b"secret-canary", "metrics_password": b"second-canary"}
        self.state_marker = None
        self.validation_fails = False
        self.ready = True
        self.stack.enter_context(patch.object(deploy, "run", side_effect=self.run_command))
        self.stack.enter_context(patch.object(deploy, "compose_command", return_value=["fixture-compose"]))
        self.stack.enter_context(patch.object(deploy, "check_mount"))
        self.stack.enter_context(patch.object(deploy, "read_secret", side_effect=lambda *args: self.passwords.copy()))
        self.stack.enter_context(patch.object(deploy, "listeners", side_effect=lambda port, pid=0: [bool(pid)] if self.record and self.record["State"]["Running"] else []))
        self.stack.enter_context(patch.object(deploy, "health", side_effect=lambda port: self.ready))
        self.stack.enter_context(patch.object(deploy.shutil, "disk_usage", return_value=argparse.Namespace(free=123456789)))

    def model(self, directory):
        environment = dict(line.split("=", 1) for line in (directory / "customer.env").read_text().splitlines()
                           if line and not line.startswith("#"))
        service = {"image": "grafana/alloy:v1.18.0", "network_mode": "host", "environment": environment,
                   "command": ["run", "--server.http.listen-addr=127.0.0.1:12345"], "volumes": []}
        if (directory / "image.override.yaml").exists():
            service.update(json.loads((directory / "image.override.yaml").read_text())["services"]["alloy"])
        if (directory / "runtime.override.yaml").exists():
            service.update(json.loads((directory / "runtime.override.yaml").read_text())["services"]["alloy"])
        return {"services": {"alloy": service}, "volumes": {"alloy-data": {"external": True, "name": "fixture-data"}}}

    def run_command(self, command, seconds=30):
        self.calls.append(command)
        if command[0] == "fixture-compose":
            directory = Path(command[command.index("--project-directory") + 1])
            if command[-3:] == ["config", "--format", "json"]:
                return json.dumps(self.model(directory))
            self.assertEqual(command[-7:], ["up", "-d", "--no-deps", "--no-build", "--pull", "never", "alloy"])
            service = self.model(directory)["services"]["alloy"]
            if not self.record or self.record["release"] != str(directory):
                self.starts += 1
                self.record = {"Id": str(self.starts), "release": str(directory), "Image": self.digest,
                               "Config": {"Labels": dict(service["labels"], **{"com.docker.compose.service": "alloy"}),
                                          "Cmd": service["command"]},
                               "State": {"Running": True, "Pid": 999, "Status": "running"},
                               "Mounts": [{"Destination": "/var/lib/alloy/data", "Name": "fixture-data"}]}
            self.record["State"].update(Running=True, Pid=999, Status="running")
            return ""
        self.assertEqual(command[0], "docker")
        action = command[1:]
        if action[0] == "ps":
            self.assertIn("label=com.docker.compose.project=alloy-fixture", action)
            return self.record["Id"] if self.record else ""
        if action[0] == "inspect":
            return json.dumps([self.record])
        if action[:2] == ["volume", "ls"]:
            return "\n".join(self.volumes)
        if action[:2] == ["volume", "inspect"]:
            return json.dumps([self.volumes[action[2]]])
        if action[:2] == ["volume", "create"]:
            name = action[-1]
            self.volumes[name] = {"Labels": {deploy.LABEL: self.args.project}, "Driver": "local", "Options": None,
                                  "Mountpoint": str(deploy.DATA / name / "_data")}
            return name
        if action[0] == "pull":
            return ""
        if action[:2] == ["image", "inspect"]:
            return json.dumps([{"RepoDigests": [action[2] if "@" in action[2] else self.digest]}])
        if action[0] == "run":
            if "validate" in action:
                self.assertIn("--network", action)
                self.assertNotIn("/var/run/docker.sock", " ".join(action))
                if self.validation_fails:
                    raise deploy.Error("Fixture invalid Alloy configuration")
            elif "chmod" in action[-1]:
                self.assertIsNone(self.state_marker, "Existing state was reinitialized")
                self.state_marker = "retained-marker"
            return ""
        self.fail(f"Unexpected command {command}")

    def operate(self, action="deploy"):
        self.args.action = action
        with redirect_stdout(io.StringIO()) as output:
            deploy.operate(self.args)
        self.assertNotIn("secret-canary", output.getvalue())
        return output.getvalue()

    def test_plan_is_observational_and_refuses_unapproved_fresh_state(self):
        self.args.create_state = False
        with self.assertRaisesRegex(deploy.Error, "Expected state volume"):
            self.operate("plan")
        self.args.create_state = True
        self.operate("plan")
        self.assertFalse(self.root.exists())
        self.assertFalse(self.volumes)
        self.assertFalse(any(c[:2] in (["docker", "pull"], ["docker", "run"]) for c in self.calls))
        deploy.read_secret.assert_not_called()

    def test_install_rerun_update_and_rollback(self):
        self.operate()
        first = deploy.pointer(self.root, "current")
        self.assertEqual((first / "credentials/loki_password").stat().st_mode & 0o777, 0o600)
        self.operate()
        self.assertEqual(self.starts, 1)
        self.assertIsNone(deploy.pointer(self.root, "previous"))
        self.record["State"]["Running"] = False
        self.operate()
        self.assertEqual(self.starts, 1)
        self.assertTrue(self.record["State"]["Running"])
        self.passwords["metrics_password"] = b"rotated-canary"
        self.operate()
        second = deploy.pointer(self.root, "current")
        self.assertNotEqual(first, second)
        self.assertEqual(deploy.pointer(self.root, "previous"), first)
        self.operate()
        self.assertEqual(self.starts, 2)
        self.assertEqual(deploy.pointer(self.root, "previous"), first)
        self.operate("rollback")
        self.assertEqual(deploy.pointer(self.root, "current"), first)
        self.assertEqual(deploy.pointer(self.root, "previous"), second)
        self.assertEqual(self.state_marker, "retained-marker")

    def test_missing_container_and_boot_reconstruction_reuse_state(self):
        self.operate()
        self.record = None
        self.operate()
        self.assertEqual(self.starts, 2)
        self.args.root = self.directory / "fresh-boot"
        self.root = self.args.root / self.args.project
        self.record = None
        self.args.create_state = False
        self.operate()
        self.assertEqual(self.starts, 3)
        self.assertEqual(self.state_marker, "retained-marker")

    def test_missing_expected_state_never_implicitly_recreated(self):
        self.operate()
        self.volumes.clear()
        self.record = None
        with self.assertRaisesRegex(deploy.Error, "Expected state volume"):
            self.operate()
        self.assertFalse(self.volumes)

    def test_unmanaged_container_and_wrong_volume_refused(self):
        self.operate()
        self.record["Config"]["Labels"].pop(deploy.LABEL)
        with self.assertRaisesRegex(deploy.Error, "Unmanaged project"):
            self.operate()
        self.record = None
        self.volumes["fixture-data"]["Labels"] = {}
        with self.assertRaisesRegex(deploy.Error, "ownership mismatch"):
            self.operate()

    def test_invalid_config_leaves_runtime_and_successful_release(self):
        self.operate()
        previous = deploy.pointer(self.root, "current")
        runtime = copy.deepcopy(self.record)
        self.validation_fails = True
        (self.source / "config/logs.alloy").write_text("invalid")
        with self.assertRaisesRegex(deploy.Error, "invalid Alloy"):
            self.operate()
        self.assertEqual(self.record, runtime)
        self.assertEqual(deploy.pointer(self.root, "current"), previous)

    def test_failed_start_preserves_last_success_and_explicit_rollback(self):
        self.operate()
        previous = deploy.pointer(self.root, "current")
        self.passwords["loki_password"] = b"changed"
        self.ready = False
        with patch.object(deploy.time, "monotonic", side_effect=[0, 100]):
            with self.assertRaisesRegex(deploy.Error, "did not become healthy"):
                self.operate()
        self.assertEqual(deploy.pointer(self.root, "current"), previous)
        self.assertNotEqual(self.record["release"], str(previous))
        self.ready = True
        self.args.release = previous
        self.operate("rollback")
        self.assertEqual(self.record["release"], str(previous))
        self.assertEqual(self.state_marker, "retained-marker")

    def test_wrong_mount_blocks_before_inspection(self):
        deploy.check_mount.side_effect = deploy.Error("Wrong data mount")
        with self.assertRaisesRegex(deploy.Error, "Wrong data mount"):
            self.operate()
        self.assertEqual(self.calls, [])



class ValidationTest(unittest.TestCase):
    def test_cached_rollback_digest_needs_no_registry(self):
        image = "grafana/alloy@sha256:" + "a" * 64
        with patch.object(deploy, "run", return_value=json.dumps([{"RepoDigests": [image]}])) as command:
            self.assertEqual(deploy.pin_image(image), image)
            command.assert_called_once_with(["docker", "image", "inspect", image])

    def test_real_mount_validation_rejects_wrong_filesystems(self):
        valid = {"target": str(deploy.DATA), "fstype": "xfs", "uuid": "data", "options": "rw,relatime"}
        for change in ({"uuid": "wrong"}, {"fstype": "ext4"}, {"options": "ro"}, {"target": "/"}):
            with self.subTest(change=change), patch.object(deploy, "run", return_value=json.dumps({"filesystems": [dict(valid, **change)]})) as command:
                with self.assertRaises(deploy.Error):
                    deploy.check_mount("data")
                self.assertEqual(command.call_count, 1)
        with patch.object(deploy, "run", side_effect=[json.dumps({"filesystems": [valid]}), "data"]):
            with self.assertRaisesRegex(deploy.Error, "separate from the boot"):
                deploy.check_mount("data")
        with patch.object(deploy, "run", side_effect=[json.dumps({"filesystems": [valid]}), "boot", "/var/lib/docker"]):
            deploy.check_mount("data")

    def test_secret_validation_and_no_password_output(self):
        arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:test"
        identity = {"Account": "123456789012", "Arn": "arn:aws:sts::123456789012:assumed-role/ec2-access/i-123"}
        for value in ({"loki_password": "secret-canary", "metrics_password": "other"},
                      {"loki_password": "secret-canary", "metrics_password": ""}, []):
            response = {"ARN": arn, "VersionStages": ["AWSCURRENT"], "SecretString": json.dumps(value)}
            with patch.object(deploy, "run", side_effect=[json.dumps(identity), json.dumps(response)]), redirect_stdout(io.StringIO()) as output:
                if isinstance(value, dict) and value.get("metrics_password"):
                    self.assertEqual(deploy.read_secret(arn, "123456789012")["loki_password"], b"secret-canary")
                else:
                    with self.assertRaises(deploy.Error):
                        deploy.read_secret(arn, "123456789012")
                self.assertEqual(output.getvalue(), "")

    def test_foreign_listener_refused(self):
        with patch.object(deploy, "listeners", return_value=[False]):
            with self.assertRaisesRegex(deploy.Error, "another process"):
                deploy.check_ports([12345], None)

    def test_trace_address_and_external_state_requirements(self):
        model = {"services": {"alloy": {"network_mode": "host", "command": ["--server.http.listen-addr=127.0.0.1:12345"],
                  "environment": {"SOURCE_COMPOSE_PROJECT": "or", "LOKI_URL": "https://loki.example", "METRICS_URL": "https://metrics.example"}}},
                 "volumes": {"alloy-data": {"external": True, "name": "fixture-data"}}}
        self.assertEqual(deploy.check_settings(model, "fixture"), ("fixture-data", [12345]))
        for address in ("0.0.0.0:4317", "8.8.8.8:4317"):
            model["services"]["alloy"]["environment"]["OTLP_GRPC_LISTEN_ADDRESS"] = address
            with self.assertRaisesRegex(deploy.Error, "loopback or private"):
                deploy.check_settings(model, "fixture")
        model["services"]["alloy"]["environment"]["OTLP_GRPC_LISTEN_ADDRESS"] = "10.0.1.2:4317"
        with patch.object(deploy, "run", return_value='[{"addr_info":[{"local":"10.0.1.2"}]}]'):
            self.assertEqual(deploy.check_settings(model, "fixture")[1], [12345, 4317])
        with patch.object(deploy, "run", return_value="[]"):
            with self.assertRaisesRegex(deploy.Error, "not assigned"):
                deploy.check_settings(model, "fixture")


if __name__ == "__main__":
    unittest.main()
