#!/usr/bin/env python3
"""Check preflight locally using strict host-command fixtures, never Docker or AWS."""
import os
from pathlib import Path
import re
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[1]


class CloudFormationLoader(yaml.SafeLoader):
    """Preserve the two CloudFormation scalar tags used by this template."""


CloudFormationLoader.add_constructor(
    "!Ref", lambda loader, node: {"Ref": loader.construct_scalar(node)})
CloudFormationLoader.add_constructor(
    "!Sub", lambda loader, node: {"Fn::Sub": loader.construct_scalar(node)})


def check_document():
    template = yaml.load((ROOT / "cloudformation-monitoring-preflight.yml").read_text(),
                         Loader=CloudFormationLoader)
    resources = template["Resources"]
    assert sorted(r["Type"] for r in resources.values()) == [
        "AWS::IAM::RolePolicy", "AWS::SSM::Document"], "Unexpected resources"
    document = resources["MonitoringPreflight"]["Properties"]
    content = document["Content"]
    assert "parameters" not in content, "Caller-controlled commands"
    assert document["DocumentType"] == "Command" and document["UpdateMethod"] == "NewVersion"
    steps = content["mainSteps"]
    assert len(steps) == 1 and steps[0]["action"] == "aws:runShellScript"
    assert steps[0]["inputs"]["timeoutSeconds"] == "90", "Unbounded execution"
    script = steps[0]["inputs"]["runCommand"][0]["Fn::Sub"]
    assert re.findall(r"\$\{([^}]+)\}", script) == ["ExpectedDataVolumeUUID"]
    script = script.replace("${ExpectedDataVolumeUUID}", "11111111-1111-1111-1111-111111111111")
    subprocess.run(["/bin/sh", "-n"], input=script, text=True, check=True, timeout=10)
    policy = resources["MonitoringPreflightAccess"]["Properties"]["PolicyDocument"]["Statement"]
    assert len(policy) == 2, "Extra permissions"
    assert policy[0]["Action"] == "ssm:SendCommand" and policy[0]["Resource"] == [
        {"Fn::Sub": "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:document/${MonitoringPreflight}"},
        {"Fn::Sub": "arn:${AWS::Partition}:ec2:${AWS::Region}:${AWS::AccountId}:instance/${InstanceId}"},
    ], "Send scope"
    assert policy[1]["Action"] == "ssm:GetCommandInvocation" and policy[1]["Resource"] == "*"
    assert policy[1]["Condition"]["StringEquals"]["aws:RequestedRegion"] == {"Ref": "AWS::Region"}
    print("PASS document syntax, fixed commands and invocation permission scope", flush=True)
    return script


FIXTURE = r"""#!/bin/sh
tool=${0##*/}
printf '%s %s\n' "$tool" "$*" >> "$TEST_LOG"
case "$tool:$*" in
  id:-u) echo 0 ;;
  date:-u|hostname:|uname:-m|df:*|free:-h) echo fixture ;;
  timeout:15*) shift; exec "$@" ;;
  findmnt:*)
    case "$*" in
      '-rn -M / -o UUID')
        if test "$SCENARIO" = boot; then echo 11111111-1111-1111-1111-111111111111; else echo 22222222-2222-2222-2222-222222222222; fi ;;
      '-rn -M /var/lib/docker/volumes -o UUID')
        test "$SCENARIO" != missing || exit 1
        if test "$SCENARIO" = wrong; then echo different; else echo 11111111-1111-1111-1111-111111111111; fi ;;
      '-rn -M /var/lib/docker/volumes -o FSTYPE')
        if test "$SCENARIO" = filesystem; then echo ext4; else echo xfs; fi ;;
      '-rn -M /var/lib/docker/volumes -o OPTIONS')
        if test "$SCENARIO" = readonly; then echo ro,relatime; else echo rw,relatime; fi ;;
      '-rn -M /var/lib/docker/volumes -o TARGET,SOURCE,FSTYPE,UUID') echo fixture ;;
      *) exit 99 ;;
    esac ;;
  docker:version) test "$SCENARIO" != daemon ;;
  'docker:compose version') test "$SCENARIO" != standalone && test "$SCENARIO" != compose ;;
  docker-compose:version) test "$SCENARIO" = standalone ;;
  *) echo "Unexpected command: $tool $*" >&2; exit 99 ;;
esac
"""


def check_host_behavior(script):
    with tempfile.TemporaryDirectory(prefix="monitoring-preflight-test-") as temporary:
        directory = Path(temporary)
        fixture = directory / "fixture"
        fixture.write_text(FIXTURE)
        fixture.chmod(0o700)
        for name in ("id", "date", "hostname", "uname", "timeout", "findmnt",
                     "docker", "docker-compose", "df", "free"):
            (directory / name).symlink_to(fixture)
        for scenario in ("plugin", "standalone", "missing", "wrong", "boot",
                         "filesystem", "readonly", "daemon", "compose"):
            log = directory / f"{scenario}.log"
            environment = os.environ.copy()
            environment.update(PATH=str(directory), SCENARIO=scenario, TEST_LOG=str(log))
            result = subprocess.run(["/bin/sh"], input=script, text=True,
                                    capture_output=True, env=environment, timeout=10)
            expected_success = scenario in ("plugin", "standalone")
            assert (result.returncode == 0) == expected_success, (
                f"{scenario}: {result.stdout} {result.stderr}")
            marker = "PASS monitoring host prerequisites; no services changed"
            assert (marker in result.stdout) == expected_success, f"{scenario}: false success"
            if scenario in ("missing", "wrong", "boot", "filesystem", "readonly"):
                assert not re.search(r"^docker", log.read_text(), re.MULTILINE), (
                    f"{scenario}: Docker accessed before mount acceptance")
            print(f"PASS {scenario} host prerequisite behavior", flush=True)


def check_workflow():
    workflow = yaml.safe_load((ROOT / "monitoring-preflight.yml.example").read_text())
    for step in workflow["jobs"]["preflight"]["steps"]:
        if "run" in step:
            subprocess.run(["/bin/bash", "-n"], input=step["run"], text=True,
                           check=True, timeout=10)
    print("PASS workflow shell syntax", flush=True)


if __name__ == "__main__":
    check_host_behavior(check_document())
    check_workflow()
