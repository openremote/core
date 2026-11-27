#!/usr/bin/env python3
"""Exercise the actual document Python with local archives and fake AWS/host commands."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch

import yaml
from test_preflight import CloudFormationLoader

TEMPLATE = Path(__file__).resolve().parents[1] / "cloudformation-monitoring-deploy.yml"
VALUES = {"AWS::Partition": "aws", "AWS::Region": "eu-west-1", "AWS::AccountId": "123456789012",
          "EnvironmentName": "staging", "MonitoringRoleName": "monitoring-staging",
          "EC2RoleName": "ec2-access-eu-west-1", "InstanceId": "i-0123456789abcdef0",
          "ProjectName": "alloy-customer-staging", "ExpectedDataVolumeUUID": "11111111-1111-1111-1111-111111111111",
          "MonitoringSecretArn": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:monitoring-fixture-abcdef"}


def load():
    template = yaml.load(TEMPLATE.read_text(), Loader=CloudFormationLoader)
    document = template["Resources"]["MonitoringDeploy"]["Properties"]["Content"]
    shell = document["mainSteps"][0]["inputs"]["runCommand"][0]["Fn::Sub"]
    for key, value in VALUES.items():
        shell = shell.replace("${" + key + "}", value)
    assert "${" not in shell and "{{" not in shell
    subprocess.run(["/bin/sh", "-n"], input=shell, text=True, check=True)
    python = shell.split("<<'PYTHON'\n", 1)[1].rsplit("\nPYTHON", 1)[0]
    module = types.ModuleType("document_test")
    exec(compile(python, str(TEMPLATE), "exec"), module.__dict__)
    return template, document, module


class DeploymentDocumentTest(unittest.TestCase):
    def setUp(self):
        self.template, self.document, self.module = load()

    def bundle(self, change=None):
        entries = [(name, b"fixture content\n", tarfile.REGTYPE) for name in sorted(self.module.REQUIRED)]
        if change:
            change(entries)
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w:gz") as archive:
            for name, content, kind in entries:
                member = tarfile.TarInfo(name)
                member.type = kind
                member.size = len(content) if kind == tarfile.REGTYPE else 0
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    member.linkname = "/etc/passwd"
                archive.addfile(member, io.BytesIO(content))
        return data.getvalue()

    def exercise(self, payload=None, *, action="plan", options=None, scenario=None):
        payload = payload if payload is not None else self.bundle()
        settings = dict(SSM_Action=action, SSM_ReleaseId="review-123-1", SSM_VersionId="v1.+/=",
                        SSM_SHA256=hashlib.sha256(payload).hexdigest(), SSM_CreateState="false",
                        SSM_EnableTraces="false", AWS_ACCESS_KEY_ID="must-not-inherit", AWS_PROFILE="ignored")
        settings.update(options or {})
        calls, extracted = [], []

        def run(command, **kwargs):
            calls.append(command)
            env = kwargs["env"]
            self.assertNotIn("AWS_ACCESS_KEY_ID", env)
            self.assertNotIn("AWS_PROFILE", env)
            self.assertEqual(env["AWS_CONFIG_FILE"], "/dev/null")
            self.assertEqual(env["AWS_SHARED_CREDENTIALS_FILE"], "/dev/null")
            if command[0] == "aws":
                self.assertIn("--region", command)
                self.assertIn("eu-west-1", command)
                if "get-caller-identity" in command:
                    response = {"Arn": "wrong" if scenario == "identity" else self.module.CALLER}
                else:
                    for flag, value in {"--expected-bucket-owner": "123456789012",
                                        "--bucket": "openremote-monitoring-123456789012-eu-west-1",
                                        "--key": "staging/releases/review-123-1/bundle.tar.gz",
                                        "--version-id": "v1.+/=", "--range": "bytes=0-8388608"}.items():
                        self.assertEqual(command[command.index(flag) + 1], value)
                    path = Path(command[-1])
                    self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
                    path.write_bytes(b"corrupt" if scenario == "checksum" else payload)
                    response = {"VersionId": "wrong" if scenario == "version" else "v1.+/="}
                    if scenario == "download":
                        return subprocess.CompletedProcess(command, 1, "", "PRIVATE ERROR")
                return subprocess.CompletedProcess(command, 0, json.dumps(response), "")
            self.assertEqual(command[1:4], ["-E", "-s", "-B"])
            source = Path(command[command.index("--source") + 1])
            extracted.append(source)
            self.assertEqual(command[4], str(source / "deploy.py"))
            self.assertEqual(command[5], action)
            self.assertEqual(command[command.index("--project") + 1], VALUES["ProjectName"])
            self.assertEqual(command[command.index("--root") + 1], "/opt/openremote/monitoring")
            self.assertEqual(command[command.index("--data-uuid") + 1], VALUES["ExpectedDataVolumeUUID"])
            for name in self.module.REQUIRED:
                self.assertEqual((source / name).stat().st_mode & 0o777, 0o600)
            self.assertEqual("--secret-arn" in command, action == "deploy")
            self.assertEqual("--create-state" in command, settings["SSM_CreateState"] == "true")
            self.assertEqual("--traces" in command, settings["SSM_EnableTraces"] == "true")
            if action == "deploy":
                self.assertEqual(command[command.index("--secret-arn") + 1], VALUES["MonitoringSecretArn"])
            if scenario == "timeout":
                raise subprocess.TimeoutExpired(command, 900)
            return subprocess.CompletedProcess(command, 1 if scenario == "child" else 0)

        output, failure = io.StringIO(), None
        with tempfile.TemporaryDirectory() as work:
            old_umask = os.umask(0o077)
            try:
                with patch.dict(os.environ, settings, clear=True), patch.object(self.module.sys, "platform", "linux"), \
                        patch.object(self.module.os, "geteuid", return_value=0), \
                        patch.object(self.module.tempfile, "tempdir", work), \
                        patch.object(self.module.subprocess, "run", side_effect=run), contextlib.redirect_stdout(output):
                    try:
                        self.module.main()
                    except (self.module.Error, subprocess.TimeoutExpired, tarfile.TarError) as error:
                        failure = error
                self.assertEqual(list(Path(work).iterdir()), [], "Temporary bundle retained")
            finally:
                os.umask(old_umask)
        self.assertTrue(all(not path.exists() for path in extracted))
        self.assertNotIn("PRIVATE ERROR", output.getvalue())
        return calls, failure

    def test_document_contract(self):
        resources = self.template["Resources"]
        self.assertEqual(sorted(r["Type"] for r in resources.values()), ["AWS::IAM::RolePolicy", "AWS::SSM::Document"])
        self.assertEqual(self.document["parameters"]["Action"]["default"], "plan")
        for parameter in self.document["parameters"].values():
            self.assertEqual(parameter["interpolationType"], "ENV_VAR")
            for repeat in re.findall(r"\{(\d+(?:,\d*)?)\}", parameter.get("allowedPattern", "")):
                self.assertTrue(all(int(n) <= 1000 for n in repeat.split(",") if n))
        policy = resources["MonitoringDeployAccess"]["Properties"]["PolicyDocument"]["Statement"]
        self.assertEqual(len(policy), 2)
        self.assertEqual(policy[0]["Action"], "ssm:SendCommand")
        self.assertEqual(policy[0]["Resource"], [
            {"Fn::Sub": "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:document/${MonitoringDeploy}"},
            {"Fn::Sub": "arn:${AWS::Partition}:ec2:${AWS::Region}:${AWS::AccountId}:instance/${InstanceId}"}])
        self.assertEqual(policy[1]["Action"], "ssm:GetCommandInvocation")
        self.assertEqual(policy[1]["Condition"]["StringEquals"]["aws:RequestedRegion"], {"Ref": "AWS::Region"})

    def test_plan_and_explicit_deploy(self):
        for action, options in (("plan", {}), ("deploy", {"SSM_CreateState": "true", "SSM_EnableTraces": "true"})):
            with self.subTest(action=action):
                calls, error = self.exercise(action=action, options=options)
                self.assertIsNone(error)
                self.assertEqual(len(calls), 3)

    def test_real_packaging_layout(self):
        source = TEMPLATE.parents[2] / "monitoring"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs, output = root / "inputs", root / "output"
            inputs.mkdir()
            output.mkdir()
            for name in self.module.REQUIRED:
                target = inputs / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / ("customer.env.example" if name == "customer.env" else name), target)
            archive = root / "bundle.tar.gz"
            env = dict(os.environ, COPYFILE_DISABLE="1")
            subprocess.run(["tar", "-czf", str(archive), "-C", str(inputs)] + sorted(self.module.REQUIRED),
                           env=env, check=True)
            self.module.unpack(archive, output)
            for name in self.module.REQUIRED:
                self.assertEqual((inputs / name).read_bytes(), (output / name).read_bytes())

    def test_invalid_request_stops_before_aws(self):
        for field, value in (("SSM_ReleaseId", "../escape"), ("SSM_Action", "$(id)"), ("SSM_VersionId", "null"),
                             ("SSM_VersionId", "a" * 1025), ("SSM_SHA256", "bad"), ("SSM_CreateState", "")):
            with self.subTest(field=field):
                calls, error = self.exercise(options={field: value})
                self.assertIsNotNone(error)
                self.assertEqual(calls, [])

    def test_transfer_failures_never_execute_bundle(self):
        for scenario in ("identity", "download", "version", "checksum"):
            with self.subTest(scenario=scenario):
                calls, error = self.exercise(scenario=scenario)
                self.assertIsNotNone(error)
                self.assertTrue(all(c[0] == "aws" for c in calls))

    def test_unsafe_or_incomplete_archive_never_executes(self):
        changes = [lambda e: e.pop(), lambda e: e.append(e[0]),
                   lambda e: e.append(("credentials/loki_password", b"dummy", tarfile.REGTYPE)),
                   lambda e: e.append(("../escape", b"dummy", tarfile.REGTYPE)),
                   lambda e: e.append(("/tmp/escape", b"dummy", tarfile.REGTYPE)),
                   lambda e: e.__setitem__(0, (e[0][0], b"", tarfile.SYMTYPE)),
                   lambda e: e.__setitem__(0, (e[0][0], b"", tarfile.LNKTYPE)),
                   lambda e: e.__setitem__(0, (e[0][0], b"x" * (1024**2 + 1), tarfile.REGTYPE))]
        for index, change in enumerate(changes):
            with self.subTest(case=index):
                calls, error = self.exercise(self.bundle(change))
                self.assertIsNotNone(error)
                self.assertTrue(all(c[0] == "aws" for c in calls))

    def test_child_failure_and_timeout_have_no_automatic_retry(self):
        for scenario in ("child", "timeout"):
            with self.subTest(scenario=scenario):
                calls, error = self.exercise(action="deploy", scenario=scenario)
                self.assertIsNotNone(error)
                self.assertEqual(len(calls), 3)


if __name__ == "__main__":
    unittest.main()
