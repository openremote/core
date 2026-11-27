# Monitoring AWS prerequisites

`cloudformation-monitoring-identity.yml` provisions only a dedicated monitoring role.
It requires the account's shared GitHub OIDC provider, managed through CfCT.
The identity template alone grants no SSM, S3 or Secrets Manager permissions.
The separate artifact-access and preflight templates add the scoped grants described below.
These templates do not change running services or the application deployment role.

The template and `monitoring-identity-check.yml.example` are local drafts. Review
them before creating a change set or deploying the identity stack. The first live workflow
will only assume the role and call `aws sts get-caller-identity`; it will not
invoke SSM or deploy monitoring.

## Provisioning ownership and deployment order

CfCT owns the shared account-wide GitHub provider. The separate monitoring stack
owns the repository-specific monitoring role and its trust/permissions. The existing
`create-iam-roles` customization continues to own EC2, developer and application
roles and their existing policies. The artifact-access stack owns only its additional
named inline policies, including the EC2 artifact read grant; it does not replace
CfCT policies, change role trust or modify the developers-account provider.

The provider source lives in the CodeCommit repository
`custom-control-tower-configuration`:

- `templates/github-oidc-provider.template` defines only the shared provider.
- `manifest.yaml` contains the `github-oidc-provider` resource, targeting
  the `Custom` OU in `eu-west-1`.

There is no provider template in this repository. The CodeCommit repository is the
single deployment source for it; existing manifest entries and shared IAM roles are
preserved. The provider is shared across environments within each account. This
OU-wide prerequisite does not deploy monitoring roles or collectors; their initial
rollout remains limited to the pilot customer.

Confirmed during rollout preparation:

- `Custom-Control-Tower-CodePipeline` reads this CodeCommit repository's `main`
  branch. Source polling is disabled, and the pipeline has no manual approval action.
- No CodeCommit change-trigger rule was found in EventBridge. The inspected execution
  was started by a CfCT Lambda role, consistent with lifecycle automation. This does
  not establish that every future run must be started manually: treat remote `main`
  as deployable configuration that another invocation can pick up.
- All nine active accounts in `Custom` reported no GitHub OIDC provider. There are
  no nested OUs. One closed account was excluded from provider inspection.
- The installed `CustomizationsForCTSolution` stack reports CfCT v2.8.3. Its
  [OU account filter](https://github.com/aws-solutions/aws-control-tower-customizations/blob/v2.8.3/source/src/cfct/manifest/manifest_parser.py#L721-L733)
  includes only accounts whose legacy `Status` is `ACTIVE`. The live account listing
  confirmed `ACTIVE` for all nine intended accounts and `SUSPENDED` for the closed
  account, so the standard v2.8.3 filter excludes it. No account move or manifest
  exclusion is required for this rollout.

Remaining deployment steps:

1. Review the complete CodeCommit change and merge into remote `main` only when ready
   to deploy. A feature-branch push does not change the pipeline's configured source.
   Recheck provider ownership if the target accounts or their configuration change;
   the template does not import or adopt existing providers.
2. Check whether an execution has already picked up the merged commit. If not, use
   CodePipeline **Release change** to start it explicitly. Verify its source revision
   and verify successful provider StackSet instances in all nine intended accounts
   in `eu-west-1`, with no deployment target for the closed account. Investigate any
   missing or failed active-account targets. Verify the pilot account's provider has audience
   `sts.amazonaws.com` before continuing.
3. Provision the separate monitoring-role stack in the pilot account, then run the
   identity check below. No SSM or collector deployment is included in this stage.

Future additions to the OU should also be checked for existing provider ownership.

IAM providers are account-wide. Use one stack-instance region per account and reuse
the provider across roles/environments. The template retains it on stack deletion;
a retained provider must not subsequently be recreated by another stack. Review its
users and CloudFormation ownership before any later removal or ownership transfer.
IAM retrieves the CA thumbprint because the optional thumbprint property is omitted.

## First check: staging environment and implementation branch

Use the GitHub environment `monitoring-staging` in the customer repository. Before
pushing the test workflow, create the environment under **Settings → Environments**
and configure **Deployment branches and tags → Selected branches and tags** with
branch rules for exactly:

- `main`
- `feature/custom-monitoring-deploy`

Do not add a wildcard or tag rule. If the implementation branch has a different
name, update the environment rule, workflow push filter and job condition together.
The environment must be configured explicitly before use; simply naming it in the
workflow is not sufficient to establish these restrictions. Environment feature
availability depends on repository visibility and GitHub plan.

Set the CloudFormation `GitHubSubject` to
`repo:OWNER/REPOSITORY:environment:monitoring-staging`, substituting the customer
repository and verifying its actual subject format. AWS matches this exact value
with `StringEquals`. The job's environment replaces the branch part of the standard
OIDC subject: GitHub's environment rules control allowed branches. This trust can be
used by other qualifying jobs in that repository/environment, not only this file.
Repositories with customized or immutable subjects require their actual subject.

Configure two non-secret Actions variables in `monitoring-staging`:

- `MONITORING_AWS_ROLE_ARN`: the role stack's `MonitoringRoleArn` output.
- `MONITORING_AWS_REGION`: the deployment region, initially `eu-west-1`.

After the provider and identity-only role exist, copy the example into the customer
repository as `.github/workflows/monitoring-identity-check.yml`. Push it on the exact
implementation branch to run the pre-merge identity check. The temporary `push`
trigger is restricted to that branch. No initial merge is required for this test.
The check requests temporary credentials and verifies the account and assumed role;
it prints neither credentials nor tokens and performs no deployment or secret read.

Once the workflow is on the default branch, remove its temporary `push` trigger and
use **Run workflow** (`workflow_dispatch`) for subsequent checks. Manual dispatch
requires the workflow to exist on the default branch; selecting another branch does
not bypass environment restrictions. Retain the exact implementation-branch rule
only while it is needed for testing, then remove it and the corresponding job
condition alternative. Actual monitoring deployment will be explicitly requested,
not triggered by this identity-check push. Production uses a separate environment
and role, restricted to `main`, in a later rollout.

An environment's branch rules complement AWS trust; they do not replace versioned
non-secret deployment settings in environment files. No GitHub AWS access-key
secrets are needed. Optional reviewer gates can be configured where supported, but
are not a prerequisite for this identity-only test.

## CloudFormation inputs and ownership

| Parameter       | Value                                                                                    |
| --------------- | ---------------------------------------------------------------------------------------- |
| `RoleName`      | Dedicated monitoring role name, for example `monitoring-staging`.                        |
| `GitHubSubject` | Verified exact subject for the customer repository and `monitoring-staging` environment. |

The role references the fixed GitHub provider URL using the stack's account ID and
AWS partition. No provider ARN parameter, cross-stack export or SSM lookup is needed.
The provider must already exist in that account; this stack neither creates nor
changes it. `GitHubProviderArn` is an informational output for that prerequisite.
Deleting this stack removes the dedicated monitoring role, not the shared provider.

Use the infrastructure provisioning identity to review/apply the role stack with
`CAPABILITY_NAMED_IAM`. The monitoring role cannot provision or modify itself.

## Acceptance before deployment permissions

1. Configure `monitoring-staging`, its exact allowed branches and environment variables;
   review the corresponding AWS environment subject.
2. Deploy the CfCT provider customization and verify the pilot account, then review and
   provision the monitoring-role stack in that same account.
3. Run the identity-only example from the implementation branch using its temporary
   push trigger; switch to manual dispatch once it exists on the default branch.
4. Verify `get-caller-identity` returns the intended account and assumed role.
5. Review/provision the storage and environment artifact-access stacks separately,
   then validate upload and versioned retrieval as described below. The secret stack
   grants direct EC2 credential reads. Follow the [read-only SSM preflight](preflight.md) to validate the dedicated
   command path; actual deployment permissions follow separately.

Artifact-bucket selection and monitoring password migration are not prerequisites
for this authentication-only check. ECR publishing is not part of its acceptance.

## Read-only SSM command check

`cloudformation-monitoring-preflight.yml` creates a fixed read-only document and a
policy allowing the monitoring role to invoke it on one specified EC2 instance.
`monitoring-preflight.yml.example` verifies OIDC identity, invokes a pinned numeric
document version and checks its result. It verifies the expected retained XFS mount
before Docker/Compose inspection. No arbitrary command parameters or service changes.

Follow [the preflight instructions](preflight.md) for provisioning inputs, the UUID
check, GitHub variables, permission limitations and expected results. The temporary
branch push trigger executes this read-only check after review; remove it after merge.
This is the next live acceptance step after identity, and does not require S3/secret
provisioning first. Actual deployment is still a separate, explicitly requested step.

## Monitoring deployment document

The [SSM deployment document](deployment.md) is the next increment after the identity,
preflight, artifact-transfer and direct EC2 secret-read checks. It verifies an exact
bundle version/checksum and invokes the validated host `plan` or `deploy` action.
It defaults to plan; reviewed live provisioning, workflow integration and staging
cutover remain separate steps. Bundles execute trusted code as root, so review the
permission implications before granting invocation access.

## Release artifacts and passwords

`cloudformation-monitoring-storage.yml` provisions one dedicated artifact bucket per
customer account and region. Deploy it once through the infrastructure provisioning
identity, separately from the per-environment identity stacks. This is a customer
prerequisite, not an OU-wide CfCT rollout. It does not create IAM grants, secrets,
SSM commands or collectors, and does not change application backup buckets.

The bucket is named `openremote-monitoring-<account-id>-<region>`. Use environment
prefixes and unique release IDs:

```text
staging/releases/<release-id>/bundle.tar.gz
production/releases/<release-id>/bundle.tar.gz
```

A release contains non-secret Compose/Alloy configuration and deployment files,
including the resolved image override needed for exact image selection on rollback.
The deployment request will identify the object key, S3 version ID and expected
SHA-256 checksum. Keep that selection available outside the EC2 boot disk. S3
versioning is not an immutability guarantee; do not reuse release IDs or select an
unqualified latest object when reproducing an earlier deployment.

Storage has versioning, SSE-S3 encryption, disabled ACLs, all public-access blocks
and an HTTPS-only bucket policy. The bucket and its policy are retained on stack
deletion/replacement. Retained resources need explicit ownership review before
recreating the stack; the template does not adopt an existing bucket. It expires
only incomplete multipart uploads, not completed releases or older object versions.
Define release cleanup before production while retaining current and rollback
artifacts. No customer-managed KMS key is required for this artifact bucket.

The existing pilot inventory showed an application backup bucket but no staging
bucket. Dedicated storage avoids making staging monitoring depend on production
backups. Prefixes organize environments; they do not provide access isolation by
themselves. The artifact-access template grants workflow uploads to the intended prefix
and EC2 downloads of explicitly selected versions. Existing broad instance S3 permissions
remain effective; a narrower additional Allow does not remove them.

Amazon Secrets Manager is the agreed password source. Keep one secret per environment
for Loki, Prometheus and optional Tempo credentials. EC2 will retrieve it directly
into private files mounted read-only into Alloy; GitHub needs no secret-value read
access. Passwords must not appear in release artifacts, Git, SSM command parameters
or command output. Manual rotation and the team's password-manager recovery copy
remain the initial operating model. Alternative secret backends are deferred; no
provider abstraction or synchronization service is needed.

The environment secret template below supplies direct instance credential reads.
The artifact-access template supplies the S3 grants. The [preflight document](preflight.md)
and example workflow validate read-only SSM execution; collector deployment follows
separately. The identity-only workflow remains unchanged.

Local review can check the storage template without accessing AWS. Before live
provisioning, validate it with CloudFormation and review a change set in the explicit
customer account/region: expect only a new bucket and its policy. Check for an
existing bucket with the chosen name first. After approved creation, verify versioning,
encryption, public-access blocks and the TLS policy before granting deployment access.
No collector restart or staging host test is needed for this template-only change.

## Artifact access

`cloudformation-monitoring-artifact-access.yml` adds two small inline policies to
existing roles. Deploy one stack per environment, after the dedicated monitoring role
and artifact bucket exist in the same account/region. Keeping access separate lets
the first identity check run before granting artifact permissions.

| Parameter            | Meaning                                                                                          |
| -------------------- | ------------------------------------------------------------------------------------------------ |
| `EnvironmentName`    | Prefix environment, matching the environment being deployed.                                     |
| `MonitoringRoleName` | Dedicated monitoring GitHub role from the identity stack; never the application deployment role. |
| `EC2RoleName`        | Existing role used by the intended EC2 instance.                                                 |

The template rejects identical workflow/instance role names. The bucket name follows
the storage template's account/region convention; there is no arbitrary bucket ARN
or cross-account access parameter. Each policy applies only to objects under
`<environment>/releases/` in that bucket:

| Principal                | New permission        | Intended operation                                                  |
| ------------------------ | --------------------- | ------------------------------------------------------------------- |
| Monitoring workflow role | `s3:PutObject`        | Upload one small release bundle and retain the returned version ID. |
| EC2 instance role        | `s3:GetObjectVersion` | Download that exact key/version, then verify the expected checksum. |

The intended CLI operations are `aws s3api put-object` (one request, no ACLs/tags,
SSE-S3, `--if-none-match '*'`) and `aws s3api get-object --version-id ...`.
The upload response supplies the version ID without an additional read or list call.
Reject a missing or `null` version ID; it indicates that the versioned-release
contract has not been met. Use unique release IDs and an explicit region. The bundle
contains configuration/scripts, not container images; no multipart transfer is planned.
Do not substitute `s3 sync`, an unversioned download, or bucket discovery commands
without reviewing their different permission requirements.

No list, delete, object-version delete, ACL, secret-read, IAM-management or SSM actions
are added to the workflow role. The EC2 grant adds no upload permission. These are
additional Allows, not restrictions on existing permissions: the pilot EC2 role still
has its existing broad S3 grants. `GetObject` does not authorize a download with an
explicit version ID, which is why `GetObjectVersion` is added. All instances using a
shared role receive its grants; this does not isolate staging from production.

The access stack owns only policy names
`openremote-monitoring-<region>-<environment>-artifact-upload` and
`openremote-monitoring-<region>-<environment>-artifact-download`. Do not declare those
same policies in CfCT or another stack. Existing role trust and CfCT policies stay
under their original owners. Delete the access stack before deleting either role;
removing these grants does not delete artifacts or undo other existing grants.

Before applying, verify the exact account, region, role names and bucket ownership,
and check that the two policy names are unused. Validate the template and review a
change set with IAM acknowledgement (`CAPABILITY_NAMED_IAM`): expect only two new
`AWS::IAM::RolePolicy` resources. The provisioning identity, not the workflow role,
needs permission to manage those policies.

The [artifact transfer check](artifact-check.md) supplies a separate SSM document
and staging workflow for the allowed upload/versioned-download path. Provision its
stack and configure its variables before copying/pushing the workflow. It executes
no downloaded content and leaves the read-only preflight document unchanged.

After approved provisioning, validate with a harmless uniquely named object under
`<environment>/releases/access-check-<unique-id>/`, containing no credentials or code
to execute. Use the intended monitoring role to upload and capture its version ID;
use the intended EC2 identity to retrieve that exact version and compare checksums.
Test that the dedicated workflow role cannot upload to another environment or read
monitoring secrets. Existing broad EC2 grants mean a cross-prefix denial cannot be
assumed for that role. An authorized operator can remove the test object version;
do not grant deletion to the workflow merely for test cleanup. These live checks
remain pending; no host/container lifecycle change is required.

Eventually customer infrastructure provisioning creates the account/region bucket
once and the identity, access and secret stacks when enabling monitoring for an
environment. Manual staging provisioning validates these same templates first.
Only the shared OIDC provider belongs in the OU-wide CfCT baseline. Populating
backend passwords remains an explicit operator step initially.

Artifact API references: [PutObject permissions and version IDs](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html),
[versioned GetObject permissions](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html),
[standalone role policies](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-iam-rolepolicy.html).

## Environment credentials

`cloudformation-monitoring-secrets.yml` creates an empty secret and its read policy
in a separate stack per deployment/environment. It does not populate passwords,
configure backend authentication, change the EC2 role or start a collector.

| Parameter         | Meaning                                                                                                                                             |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DeploymentName`  | Stable project identifier, for example `customer`; unique within the account.                                                                       |
| `EnvironmentName` | Environment identifier, for example `staging`.                                                                                                      |
| `EC2RoleName`     | Existing instance role in this account, for example `ec2-access-eu-west-1`; not the GitHub role. This template supports roles at the root IAM path. |

The secret name is
`openremote/monitoring/<deployment>/<environment>/credentials`. Use the stack's full
`MonitoringSecretArn` output in non-secret deployment settings. The template creates
no password version; an empty secret is not deployment-ready. It uses the default
AWS-managed `aws/secretsmanager` encryption key for same-account access.

The secret's resource policy grants only `secretsmanager:GetSecretValue` to the
specified EC2 role for this secret, with public-policy blocking enabled. The monitoring
stack owns both resources. CfCT retains ownership of the existing role, its trust
and inline policies; no extra policy is added to that role. No permissions are
added to the GitHub role, and no cross-account reads are configured. Other existing
identity grants and explicit denies still apply; this Allow is not an exclusive
list of everyone who can read the secret.

**All instances using the selected role receive this read grant.** In the pilot,
staging and production share an EC2 role. Granting it access to both secrets lets
both environments read both, despite their different names. Review that scope before
applying the stack; strict separation would require distinct instance identities or
a separately reviewed access restriction. Do not describe this as environment isolation.

The secret value is a JSON object with two non-empty string fields:

| Key                | Consumer                                 |
| ------------------ | ---------------------------------------- |
| `loki_password`    | Loki, and Tempo when traces are enabled. |
| `metrics_password` | Prometheus remote write.                 |

Usernames and endpoint URLs remain in the non-secret environment configuration.
The current trace configuration deliberately reuses the Loki username/password;
there is no separate `tempo_password` field. Store the actual passwords accepted by
the central gateways. Creating or rotating this AWS secret does not change them.

After a reviewed staging change set has been applied:

1. An authorized operator opens the secret in the AWS console and supplies the two
   keys using the password-manager copy of the existing credentials. If using a CLI,
   supply a protected local file rather than literal values in arguments or shell
   history. Never put values in CloudFormation parameters, GitHub variables, SSM
   commands or this repository.
2. Confirm a version labeled `AWSCURRENT` exists using secret metadata. Metadata
   inspection does not prove password correctness or EC2 read access.
3. In the later SSM acceptance check, retrieve from the intended instance into private
   files without printing values. Validate the two fields, then check backend delivery
   during collector rollout. That retrieval code is not part of this template change.

The provisioning identity needs secret creation, tagging and resource-policy management
permissions. Operators populating/rotating the value separately need `PutSecretValue`
and appropriate console/read access. The template grants neither operator nor GitHub
access. Use the infrastructure provisioning identity to validate and review a change
set: expect exactly a new secret and its resource policy, with no IAM role replacement
or EC2 change. Verify the role exists, the secret name is unused and the account/region
are correct. No host test is needed before reviewing this local template.

Stack deletion/replacement retains the secret and its versions but removes this
stack's resource policy. Other IAM permissions may still allow reads. A retained
secret needs explicit ownership review before creating a stack with the same name;
this template does not adopt it. Renaming deployment/environment parameters creates
a different secret and requires populating it before use.

Rotation remains manual: coordinate backend acceptance and the password-manager copy,
update the secret, explicitly deploy, and verify delivery. Existing Alloy file mounts
do not refresh merely because Secrets Manager changed. Keep the previous working
host configuration available for operator rollback; restoring an old secret version
cannot revive a backend-revoked password.

Secret references: [Empty secrets and default encryption](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-secretsmanager-secret.html),
[resource-based read access](https://docs.aws.amazon.com/secretsmanager/latest/userguide/auth-and-access_resource-policies.html).

Storage references: [S3 bucket properties](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-s3-bucket.html),
[bucket policy retention](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-s3-bucketpolicy.html).

References: [Manual workflow dispatch](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[OIDC subjects](https://docs.github.com/en/actions/reference/security/oidc),
[CfCT manifest targets](https://docs.aws.amazon.com/controltower/latest/userguide/cfct-manifest-file-resources-section.html),
[GitHub OIDC trust](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[CloudFormation OIDC provider](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-iam-oidcprovider.html),
[GetCallerIdentity](https://docs.aws.amazon.com/cli/latest/reference/sts/get-caller-identity.html).
