#!/usr/bin/env python3
"""Run the actual document shell with fake AWS responses; never contact AWS or Docker."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import yaml
from test_preflight import CloudFormationLoader

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = b"Harmless artifact fixture\n"
CALLER = "arn:aws:sts::123456789012:assumed-role/ec2-access-eu-west-1/i-0123456789abcdef0"

STUB = r'''import hashlib, json, os, pathlib, subprocess, sys, tempfile
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
scenario = os.environ["SCENARIO"]
with open(os.environ["TEST_LOG"], "a") as log:
    log.write(json.dumps([name] + args) + "\n")
if name == "id":
    assert args == ["-u"]
    print(0)
elif name == "timeout":
    assert args[0] in ("20", "45")
    sys.exit(subprocess.run(args[1:], timeout=5).returncode)
elif name == "mktemp":
    assert args == ["-d", "/tmp/openremote-artifact-check.XXXXXX"]
    print(tempfile.mkdtemp(prefix="download-", dir=os.environ["TEST_ROOT"]))
elif name == "sha256sum":
    print(hashlib.sha256(pathlib.Path(args[0]).read_bytes()).hexdigest(), args[0])
elif name == "aws":
    assert os.environ["AWS_SHARED_CREDENTIALS_FILE"] == "/dev/null"
    assert os.environ["AWS_CONFIG_FILE"] == "/dev/null"
    assert "AWS_ACCESS_KEY_ID" not in os.environ and "AWS_PROFILE" not in os.environ
    assert args[args.index("--region") + 1] == "eu-west-1"
    if args[:2] == ["sts", "get-caller-identity"]:
        print("wrong-caller" if scenario == "identity" else os.environ["TEST_CALLER"])
    else:
        assert args[:2] == ["s3api", "get-object"], args
        for flag, value in {
            "--expected-bucket-owner": "123456789012",
            "--bucket": "openremote-monitoring-123456789012-eu-west-1",
            "--key": "staging/releases/access-check-123-1/probe.txt",
            "--version-id": "v1.+/=",
            "--range": "bytes=0-4095",
        }.items():
            assert args[args.index(flag) + 1] == value, (flag, args)
        target = pathlib.Path(args[-1])
        assert target.name == "probe.txt"
        assert target.parent.parent == pathlib.Path(os.environ["TEST_ROOT"])
        assert target.parent.stat().st_mode & 0o777 == 0o700
        target.write_bytes(b"wrong" if scenario == "checksum" else b"Harmless artifact fixture\n")
        if scenario == "download":
            sys.exit(1)
else:
    raise AssertionError(name)
'''


def main():
    template = yaml.load((ROOT / "cloudformation-monitoring-artifact-check.yml").read_text(),
                         Loader=CloudFormationLoader)
    resources = template["Resources"]
    assert sorted(r["Type"] for r in resources.values()) == ["AWS::IAM::RolePolicy", "AWS::SSM::Document"]
    content = resources["MonitoringArtifactCheck"]["Properties"]["Content"]
    parameters = content["parameters"]
    for key, good in {"TestId": "123-1", "VersionId": "v1.+/=", "SHA256": "a" * 64}.items():
        assert parameters[key]["interpolationType"] == "ENV_VAR"
        pattern = parameters[key]["allowedPattern"]
        # Python accepts counted repeats above 1000; SSM's Go validator does not.
        for repeat in re.findall(r"\{(\d+(?:,\d*)?)\}", pattern):
            assert all(int(n) <= 1000 for n in repeat.split(",") if n), (
                key, "Counted repetition exceeds SSM regex limit")
        assert re.fullmatch(pattern, good)
        for bad in ("", "$(touch /tmp/injected)", "a\ncommand"):
            assert not re.fullmatch(pattern, bad)
    version = parameters["VersionId"]
    assert version["minChars"] == 1 and version["maxChars"] == 1024
    for size in (0, 1, 1000, 1024, 1025):
        accepted = (version["minChars"] <= size <= version["maxChars"]
                    and re.fullmatch(version["allowedPattern"], "a" * size) is not None)
        assert accepted == (1 <= size <= 1024), ("VersionId length", size)
    assert not re.fullmatch(parameters["TestId"]["allowedPattern"], "../escape")
    steps = content["mainSteps"]
    assert len(steps) == 1 and steps[0]["inputs"]["timeoutSeconds"] == "120"
    script = steps[0]["inputs"]["runCommand"][0]["Fn::Sub"]
    assert "{{" not in script
    for key, value in {"AWS::Partition": "aws", "AWS::Region": "eu-west-1",
                       "AWS::AccountId": "123456789012", "EnvironmentName": "staging",
                       "EC2RoleName": "ec2-access-eu-west-1", "InstanceId": "i-0123456789abcdef0"}.items():
        script = script.replace("${" + key + "}", value)
    assert "${" not in script
    subprocess.run(["/bin/sh", "-n"], input=script, text=True, check=True)
    policy = resources["MonitoringArtifactCheckAccess"]["Properties"]["PolicyDocument"]["Statement"]
    assert len(policy) == 2 and policy[0]["Action"] == "ssm:SendCommand"
    assert policy[0]["Resource"] == [
        {"Fn::Sub": "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:document/${MonitoringArtifactCheck}"},
        {"Fn::Sub": "arn:${AWS::Partition}:ec2:${AWS::Region}:${AWS::AccountId}:instance/${InstanceId}"},
    ]
    assert policy[1]["Action"] == "ssm:GetCommandInvocation" and policy[1]["Resource"] == "*"
    assert policy[1]["Condition"]["StringEquals"]["aws:RequestedRegion"] == {"Ref": "AWS::Region"}
    print("PASS document inputs, shell syntax and exact invocation scope")
    with tempfile.TemporaryDirectory(prefix="artifact-check-test-") as temporary:
        directory = Path(temporary)
        for name in ("id", "timeout", "mktemp", "sha256sum", "aws"):
            path = directory / name
            path.write_text("#!" + sys.executable + "\n" + STUB)
            path.chmod(0o700)
        for name in ("cut", "rm", "rmdir"):
            (directory / name).symlink_to(shutil.which(name))
        for scenario in ("success", "identity", "checksum", "download", "null-version", "missing-env"):
            log = directory / "commands.jsonl"
            log.write_text("")
            env = os.environ.copy()
            env.update(PATH=str(directory), SCENARIO=scenario, TEST_ROOT=str(directory),
                       TEST_LOG=str(log), TEST_CALLER=CALLER, SSM_TestId="123-1",
                       SSM_VersionId="v1.+/=", SSM_SHA256=hashlib.sha256(PAYLOAD).hexdigest(),
                       AWS_PROFILE="ignored", AWS_ACCESS_KEY_ID="dummy")
            if scenario == "null-version":
                env["SSM_VersionId"] = "null"
            if scenario == "missing-env":
                del env["SSM_TestId"]
            result = subprocess.run(["/bin/sh"], input=script, env=env, text=True,
                                    capture_output=True, timeout=15)
            success = scenario == "success"
            assert (result.returncode == 0) == success, (scenario, result.stdout, result.stderr)
            assert ("PASS exact artifact version" in result.stdout) == success
            assert not list(directory.glob("download-*")), (scenario, "Temporary copy retained")
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            if scenario in ("identity", "null-version", "missing-env"):
                assert not any(call[:3] == ["aws", "s3api", "get-object"] for call in calls)
            print("PASS " + scenario + ": expected outcome and temporary-file cleanup")
    workflow = yaml.safe_load((ROOT / "monitoring-artifact-check.yml.example").read_text())
    for step in workflow["jobs"]["artifact-check"]["steps"]:
        if "run" in step:
            subprocess.run(["/bin/bash", "-n"], input=step["run"], text=True, check=True)
    print("PASS workflow shell syntax")


if __name__ == "__main__":
    main()
