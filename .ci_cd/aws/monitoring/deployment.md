# SSM monitoring deployment

`cloudformation-monitoring-deploy.yml` connects an exact S3 bundle version to the
validated [host deployment script](../../monitoring/deployment.md). It creates one
Command document and one inline policy on the existing monitoring workflow role.
This is a local implementation awaiting CloudFormation review and live acceptance;
creating the stack does not invoke the document or change any containers.

## Scope and trust

The document defaults to `plan`. `deploy` must be explicitly selected. It checks the
instance's caller identity, downloads a versioned bundle, verifies its SHA-256,
extracts only the expected files into a private temporary directory and invokes
`deploy.py`. The script owns all mount, container, state and lifecycle checks; the
document does not implement another installer, lock or recovery protocol.

**Deployment bundles contain trusted code executed as root.** The checksum detects
incorrect downloads; it does not authenticate an independent publisher when the
caller supplies both bundle and checksum. A principal with artifact upload and this
SSM invocation permission can execute code on the selected instance, including by
submitting a modified script with `Action=plan`. Archive validation and fixed command
arguments do not sandbox that code. Review changes to the core revision,
customer configuration and workflow, and retain the existing GitHub environment/OIDC
restrictions. Grant this policy only to the dedicated monitoring deployment role.

The policy permits `SendCommand` on the named document and exact instance, plus
regional `GetCommandInvocation`. It adds no S3, Secrets Manager or IAM management
actions. Existing identity/artifact-access/secret stacks supply those prerequisites.
Previous preflight and artifact-check documents are unchanged. All existing role
permissions remain effective; these additional Allows are not exclusive restrictions.

## Fixed CloudFormation settings

| Parameter                | Source                                                                 |
| ------------------------ | ---------------------------------------------------------------------- |
| `EnvironmentName`        | The environment prefix used by the artifact-access stack.              |
| `MonitoringRoleName`     | Existing dedicated GitHub monitoring role.                             |
| `EC2RoleName`            | Existing instance role, verified by STS before download.               |
| `InstanceId`             | Exact SSM-managed instance; update after an instance replacement.      |
| `ProjectName`            | Independent monitoring Compose project, never the application project. |
| `ExpectedDataVolumeUUID` | Independently verified retained XFS filesystem UUID.                   |
| `MonitoringSecretArn`    | Full ARN from the populated environment secret stack.                  |

The bucket is `openremote-monitoring-<account>-<region>` and the installation root is
`/opt/openremote/monitoring`. The secret must belong to this account and region.
Callers cannot override these settings through command parameters. Passwords are
never included in the document, invocation or artifact. EC2 retrieves them directly
only when `deploy.py deploy` reaches secret preparation. `plan` does not read them.

Use the infrastructure provisioning identity to validate and review a change set
with `CAPABILITY_NAMED_IAM`. Expect only `AWS::SSM::Document` and `AWS::IAM::RolePolicy`.
Verify that the document name `openremote-monitoring-<environment>-deploy` and policy
name `openremote-monitoring-<region>-<environment>-deploy` are not owned elsewhere.
The document uses `UpdateMethod: NewVersion`; always select and record a numeric
SSM document version in the eventual workflow, not `$LATEST` or `$DEFAULT`.

## One ordinary bundle

The gzip-compressed tar archive contains these paths at its root:

```text
deploy.py
compose.yaml
compose.traces.yaml
customer.env
config/logs.alloy
config/host-metrics.alloy
config/traces.alloy
image.override.yaml          # optional resolved digest for exact reconstruction
```

Copy `deploy.py`, Compose and Alloy files from the reviewed core revision;
copy `customer.env` from the selected customer configuration. Include the trace files
even when traces are disabled. No extra manifest or nested archive is required.
`image.override.yaml`, when supplied, selects the retained resolved image digest.
Without it, normal deployment resolves the configured version tag on the host.

Package the explicit files from a prepared directory, not the entire checkout or a
host release directory. For example, with `bundle-input` containing the seven required
files above, run from its parent directory:

```sh
COPYFILE_DISABLE=1 tar -czf bundle.tar.gz -C bundle-input \
  deploy.py compose.yaml compose.traces.yaml customer.env \
  config/logs.alloy config/host-metrics.alloy config/traces.alloy
```

Append `image.override.yaml` to the explicit file list when reconstructing a resolved
release. `COPYFILE_DISABLE=1` avoids Apple metadata entries when packaging on macOS.
Never include `credentials/`, password values, tests or `runtime.override.yaml`.
Allowlisted filenames alone cannot prove that an operator has kept file contents
non-secret; review `customer.env` and all packaged files before uploading.

The document accepts at most 8 MiB downloaded, 1 MiB per regular file and 4 MiB total
file contents. It rejects duplicate names, missing required files, unknown paths,
links, sparse files and special entries. It writes files itself with private modes,
without applying archive ownership or using unrestricted `extractall`.

Upload once to `<environment>/releases/<unique-release-id>/bundle.tar.gz` using the
existing versioned `PutObject` path. Keep the returned non-null version ID and the
SHA-256 of the exact archive. Do not overwrite/reuse release IDs. Selecting the object
version prevents a later upload at the same key from changing this request.

## Invocation and failure behavior

| Command parameter | Meaning                                                                                            |
| ----------------- | -------------------------------------------------------------------------------------------------- |
| `Action`          | `plan` (default) or explicitly requested `deploy`.                                                 |
| `ReleaseId`       | 1–100 letters/digits/underscore/hyphen, starting with a letter/digit; no path.                     |
| `VersionId`       | Exact non-null S3 object version returned by upload.                                               |
| `SHA256`          | Lowercase SHA-256 of the archive.                                                                  |
| `CreateState`     | `false` by default; `true` only for an explicitly reviewed fresh installation, including its plan. |
| `EnableTraces`    | `false` by default; select `true` consistently for plan/deploy when trace settings are reviewed.   |

SSM parameters use `ENV_VAR` interpolation and are validated again in Python. Require
an agent supporting this interpolation; no fallback inserts input into shell code.
The wrapper passes an environment with only fixed system paths and instance-role AWS
configuration to child commands. AWS responses/errors and file contents are not
printed. The reviewed host script's safe output goes to the SSM result, together with
the non-secret object key/version/checksum selection.

Download/identity commands have bounded timeouts. The host action has 900 seconds
within a 1200-second SSM step. Normal success/failure removes the temporary bundle;
installed release files remain under the host script's ownership. A hard kill/reboot
can leave a private temporary directory. Timeouts or interruption do not prove that
Docker work stopped: inspect actual host status before retrying. No automatic
rollback, volume removal, release cleanup or application restart is performed here.

Use the existing host `status` and explicit `rollback` commands for operator recovery.
This first SSM increment exposes only bundle-based plan/deploy. Local readiness is
not proof of backend delivery. No resolved-artifact export or customer workflow is
included yet; retain that remaining work before production/rebuild acceptance.

## Validation and next live step

Run the local test from the core repository, in an environment with
`tests/requirements.txt` installed:

```sh
python3 -B .ci_cd/aws/monitoring/tests/test_deploy_document.py
```

It executes the document's actual Python using local archives and fake AWS/host
commands; no AWS or Docker access. The host script's real Docker lifecycle is tested
separately by its disposable staging check.

After this change is reviewed, provision the stack and wire the customer workflow to
upload the reviewed bundle and invoke **plan only** with a pinned document version.
Agree the existing staging collector's manual cutover before any `deploy` invocation.
A plan blocked by the existing unmanaged collector is an expected outcome until that
cutover. The workflow and live acceptance instructions are the next increment.

References: [SSM parameter interpolation](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-syntax-data-elements-parameters.html),
[versioned GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).
