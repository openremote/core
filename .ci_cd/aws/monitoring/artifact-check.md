# Monitoring artifact transfer check

Run this after the identity and read-only SSM preflight pass, and after the storage
and artifact-access stacks are ready. This is a staging test, not an installer.
GitHub uploads a small text file; EC2 downloads its explicit S3 version, verifies
SHA256 and removes the temporary copy. It never extracts or executes the content,
retrieves passwords, or changes Docker, Alloy, application files or services.

## 1. Provision the test document

Create a separate CloudFormation stack, for example `monitoring-staging-artifact-check`,
using `cloudformation-monitoring-artifact-check.yml` in the same account/region:

| Parameter            | Value                                                                  |
| -------------------- | ---------------------------------------------------------------------- |
| `EnvironmentName`    | `staging`                                                              |
| `MonitoringRoleName` | Existing dedicated monitoring workflow role, e.g. `monitoring-staging` |
| `EC2RoleName`        | Existing instance role, e.g. `ec2-access-eu-west-1`                    |
| `InstanceId`         | Independently verified staging instance ID, as used for preflight      |

Review with `CAPABILITY_NAMED_IAM`: expect one new Command document and one inline
policy on the monitoring role. There is no association or automatic execution.
The existing read-only preflight document is unchanged. This check fixes the bucket,
prefix, expected caller and permitted instance through provisioning, not workflow input.
The new SendCommand grant is limited to this document and instance; result reads
have the same region-limited `GetCommandInvocation` scope as preflight.

Wait for CREATE_COMPLETE. Inspect the document named by `ArtifactCheckDocumentName`
in Systems Manager and record its reviewed numeric version (normally `1` on creation).
IAM permits all versions of that document, so keep every version limited to this
check. Deployment will use a separate document. Do not replace a failed check with
an unrestricted shell command.

EC2 needs AWS CLI, coreutils and SSM Agent with ENV_VAR support (3.3.2746.0 or later).
The check installs nothing. It ignores local AWS credential files/overrides and
requires STS to identify the configured instance role with the instance ID session.
The small download is limited to 4 KiB, uses a private `/tmp` directory on the boot
filesystem, and is removed on success and ordinary failure. Forced termination or
host loss can leave a small temporary directory; no persistent data volume is used.

## 2. Configure the workflow

Keep the existing `monitoring-staging` environment, branch restrictions and variables:
`MONITORING_AWS_REGION`, `MONITORING_AWS_ROLE_ARN`, `MONITORING_EC2_INSTANCE_ID`.
Add two environment variables:

- `MONITORING_ARTIFACT_CHECK_DOCUMENT_NAME`: stack document output.
- `MONITORING_ARTIFACT_CHECK_DOCUMENT_VERSION`: reviewed numeric version.

Copy `monitoring-artifact-check.yml.example` into the customer repository as
`.github/workflows/monitoring-artifact-check.yml` after the stack is ready. Review,
commit and push separately. The temporary feature-branch push trigger **executes the
check**. Remove it after merging to main and use manual Run workflow. Neither a
checkout nor an SSH key is needed. The bucket name is derived from the verified
account and region; no additional bucket variable is required.

The workflow writes `staging/releases/access-check-<run-id>-<attempt>/probe.txt`,
using PutObject with `If-None-Match: *`, and takes VersionId directly from its response.
Missing/null version IDs fail before SSM. It sends only the test ID, version ID and
checksum through SSM ENV_VAR parameters; these are not shell source or credentials.

## 3. Check the result

Expect SSM `Success`, response code `0`, and these messages:

- `PASS expected EC2 instance role: ...` with the intended role and instance.
- `PASS exact artifact version and checksum; temporary copy removed; no services changed`

Save the workflow URL. The log records bucket, key, version, checksum and command ID.
A failed wait is not proof the remote command stopped: inspect that invocation in
SSM before retrying. The workflow does not resend automatically. If checksum or
access fails, inspect permissions/configuration rather than substituting an
unversioned download. No passwords or downloaded content are printed.

The harmless S3 object remains, because the workflow has no delete permission.
An authorized operator may remove the exact printed version using S3's versions view.
No container/volume cleanup is needed. This proves the allowed transfer path only;
it does not prove cross-environment denial or secret access. The shared EC2 role's
existing broad S3 grants remain in effect. Delete this test stack when no longer
needed; storage, artifact access and read-only preflight are separate stacks.

## Local validation

Using the same Python environment as the preflight tests:

```sh
python3 -m pip install -r .ci_cd/aws/monitoring/tests/requirements.txt
python3 .ci_cd/aws/monitoring/tests/test_artifact_check.py
```

Tests run the document shell with fake AWS responses, checking version/owner/prefix
arguments, wrong identity, checksum/download failures, cleanup and missing inputs.
They never contact AWS or Docker. Actual CloudFormation and GitHub/SSM execution
remain the live acceptance checks above.

References: [SSM parameter handling](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-syntax-data-elements-parameters.html),
[ENV_VAR agent support](https://docs.aws.amazon.com/systems-manager/latest/userguide/parameter-troubleshooting.html),
[versioned GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).
