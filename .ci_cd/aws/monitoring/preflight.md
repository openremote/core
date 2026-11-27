# Read-only monitoring preflight

This checks the GitHub → SSM → EC2 path without deploying Alloy. The document reads
identity, Docker/Compose versions, the data mount and disk/memory availability. It
runs no container, mount, volume, package, secret or artifact operations. SSM itself
still records execution history and writes its normal agent execution files/logs.

## 1. Confirm the target and prerequisites

Complete the identity-only workflow first. The dedicated monitoring role and an
Online SSM managed node are prerequisites; the artifact/secret stacks are not needed
for this check. Use staging first. From your existing connection to that host, run:

```sh
hostname
sudo -n findmnt -rn -M /var/lib/docker/volumes -o TARGET,SOURCE,FSTYPE,UUID
sudo -n findmnt -rn -M / -o TARGET,SOURCE,FSTYPE,UUID
```

Record the data filesystem UUID after confirming this is the intended retained data
volume. It must be a separate XFS filesystem, not a directory on the boot filesystem.
A filesystem UUID is not an EBS volume ID, instance ID or NVMe device name. Keep it in
non-secret environment provisioning settings. The command does not discover and
accept whatever disk happens to be mounted at deployment time.

## 2. Review and provision the preflight stack

Use `cloudformation-monitoring-preflight.yml` in the intended account/region:

| Parameter                | Source                                                              |
| ------------------------ | ------------------------------------------------------------------- |
| `EnvironmentName`        | `staging` for the first rollout.                                    |
| `MonitoringRoleName`     | Existing dedicated monitoring role from the identity stack.         |
| `InstanceId`             | Exact staging instance, independently checked against provisioning. |
| `ExpectedDataVolumeUUID` | Verified UUID from step 1.                                          |

Have the provisioning identity validate the template and review a change set with
`CAPABILITY_NAMED_IAM`. Expect one Command document and one new inline policy on the
monitoring role. Applying the stack creates no association and executes no command.
The stack owns only its new policy/document, not the role. Never use the app role.

The policy permits SendCommand only for this document and instance. GetCommandInvocation
requires Resource `*`; AWS does not support resource-level scoping for this action.
It is restricted to the stack region, but can read other invocations there if their
IDs/targets are known. No listing, arbitrary AWS-RunShellScript, Session Manager,
PassRole or document-edit permissions are added. Other existing grants still apply.

Record the outputs `PreflightDocumentName` and `PreflightInstanceId`. With provisioning
credentials, inspect the created document and record its reviewed **numeric** version:

```sh
aws ssm describe-document --name "$PREFLIGHT_DOCUMENT_NAME" \
  --profile "$AWS_PROFILE" --region "$AWS_REGION" \
  --query 'Document.{Name:Name,Status:Status,Default:DefaultVersion,Latest:LatestVersion}'
aws ssm get-document --name "$PREFLIGHT_DOCUMENT_NAME" \
  --document-version "$PREFLIGHT_DOCUMENT_VERSION" --document-format YAML \
  --profile "$AWS_PROFILE" --region "$AWS_REGION" --query Content --output text
```

Set these shell variables to the reviewed values; version `1` is only appropriate
for an initial document. Check the rendered UUID and fixed commands. CloudFormation
updates create a new default version; they must not silently move the workflow to
it. IAM authorizes the document, not one numeric version. Keep this document read-only
in all versions; actual deployment gets its own separately reviewed permission.

## 3. Configure and run the staging workflow

Reuse the already configured `monitoring-staging` GitHub environment and its exact
allowed branches. Retain `MONITORING_AWS_ROLE_ARN` and `MONITORING_AWS_REGION`. Add:

- `MONITORING_EC2_INSTANCE_ID`: the stack's instance output.
- `MONITORING_PREFLIGHT_DOCUMENT_NAME`: the document output.
- `MONITORING_PREFLIGHT_DOCUMENT_VERSION`: the reviewed numeric version.

After the stack is approved and ready, copy `monitoring-preflight.yml.example` into
the customer repository as `.github/workflows/monitoring-preflight.yml`. Review the
exact implementation branch before pushing. Its temporary push trigger **will run
the read-only command**, unlike the identity-only example. The example is deliberately
staging-only and uses the same OIDC identity verification. It checks no secrets and
needs no SSH key. Runs serialize without cancelling an active check.

Once present on the default branch, remove the temporary push trigger and use manual
**Run workflow**. Keep the environment branch rules aligned with the workflow guard.
Do not broaden the example to production as part of this test.

## 4. Interpret the result

Expect SSM Status `Success`, ResponseCode `0`, both PASS messages and the intended
host/UUID. Compose may be the plugin or standalone executable. Memory and disk values
are observations, not capacity acceptance thresholds; review them before rollout.
The check verifies CLI availability, not full Compose feature or Alloy compatibility.

The workflow prints the command ID before waiting and checks the actual invocation
result, not just SendCommand success. A failed wait or missing result does not prove
the remote command stopped. Inspect that command ID in SSM before retrying; the
workflow never automatically resends it. The document has a 90-second execution
limit and Docker commands have individual limits. Agent delivery can take longer
than execution, and the caller can fail independently.

A missing/wrong mount fails before Docker inspection. Correct provisioning rather
than changing the expected UUID merely to make the check pass. A success establishes
the identity/command/result path and host prerequisites only: S3 retrieval, secret
access and collector deployment remain separate acceptance checks.

After an AMI replacement, update the allowed InstanceId and workflow variable through
reviewed provisioning. The UUID should remain the same when the data filesystem is
retained. Delete this stack before deleting its monitoring role. No cleanup of
containers, volumes or application files is needed for this check.

## Local validation

From the core repository, using a local Python virtual environment, run:

```sh
python3 -m pip install -r .ci_cd/aws/monitoring/tests/requirements.txt
python3 .ci_cd/aws/monitoring/tests/test_preflight.py
```

This uses Python with PyYAML for parsing the templates, and stub host commands in
a temporary local directory. PyYAML is a local test dependency, not an EC2 runtime
dependency. The test checks document/permission scope, both Compose forms, mount failures,
Docker/Compose failures and shell syntax; it never calls Docker or AWS. CloudFormation
and actual SSM/GitHub execution validation remain live provisioning checks.

References: [SSM document updates](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-document.html),
[SSM action permissions](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ssm.html).
