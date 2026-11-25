# Monitoring AWS identity

`cloudformation-monitoring-identity.yml` provisions only a dedicated monitoring role.
It requires the account's shared GitHub OIDC provider, managed through CfCT.
This first slice grants no
SSM, S3, Secrets Manager or other deployment permissions. It leaves application
roles, EC2 roles and running services unchanged.

The template and `monitoring-identity-check.yml.example` are local drafts. Review
them before creating a change set or deploying the identity stack. The first live workflow
will only assume the role and call `aws sts get-caller-identity`; it will not
invoke SSM or deploy monitoring.

## Provisioning ownership and deployment order

CfCT owns the shared account-wide GitHub provider. The separate monitoring stack
owns the repository-specific monitoring role and its trust/permissions. The existing
`create-iam-roles` customization continues to own EC2, developer and application
roles; this change does not modify those roles or the developers-account provider.

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

## First check: protected main branch

The initial workflow uses branch-based trust, without a GitHub environment. Use the
exact subject `repo:OWNER/REPOSITORY:ref:refs/heads/main`, substituting the customer
repository and verifying its actual subject format. The example runs only from
`main`; branch selection in the dispatch UI does not broaden AWS trust. A branch
subject permits qualifying jobs on that branch, not only this workflow file.

After reviewing and provisioning the identity stack, copy the example to the customer
repository as `.github/workflows/monitoring-identity-check.yml` and merge it through
normal review. Configure two non-secret repository Actions variables:

- `MONITORING_AWS_ROLE_ARN`: the stack's `MonitoringRoleArn` output.
- `MONITORING_AWS_REGION`: the deployment region, initially `eu-west-1`.

Dispatch **Check monitoring AWS identity** on `main`. It requests a short-lived
session and verifies the account and assumed role before printing their identifiers.
It does not print credentials or tokens. Workflow dispatch requires the workflow to
be present on the repository's default branch. Any later choice of another trusted
branch must update the workflow condition and CloudFormation subject together.

## Choose the trust scope

`GitHubSubject` is an exact value matched with `StringEquals`, not a wildcard.
Confirm the repository's OIDC settings before selecting it. Standard examples are:

- Environment: `repo:example/customer:environment:monitoring-staging`.
- Branch, with no job environment: `repo:example/customer:ref:refs/heads/main`.

A GitHub environment is repository deployment configuration, not an AWS resource.
It can hold non-secret role/region variables and enforce deployment branch rules.
Approval features depend on the repository visibility and GitHub plan. A workflow
input or shell variable called ENVIRONMENT does not create this job environment.

When the job uses an environment, that environment replaces the branch part of the
standard subject. Configure allowed branches in GitHub; an environment subject
alone does not restrict branches or uniquely identify one workflow. Repositories
using immutable or customized subjects require their actual subject instead of
copying the examples above. Do not log OIDC tokens or AWS credentials.

## CloudFormation inputs and ownership

| Parameter | Value |
| --- | --- |
| `RoleName` | Dedicated monitoring role name, for example `monitoring-staging`. |
| `GitHubSubject` | Verified exact subject for the intended repository and environment/branch. |

The role references the fixed GitHub provider URL using the stack's account ID and
AWS partition. No provider ARN parameter, cross-stack export or SSM lookup is needed.
The provider must already exist in that account; this stack neither creates nor
changes it. `GitHubProviderArn` is an informational output for that prerequisite.
Deleting this stack removes the dedicated monitoring role, not the shared provider.

Use the infrastructure provisioning identity to review/apply the role stack with
`CAPABILITY_NAMED_IAM`. The monitoring role cannot provision or modify itself.

## Acceptance before deployment permissions

1. Agree environment or branch trust and review the corresponding GitHub settings.
2. Deploy the CfCT provider customization and verify the pilot account, then review and
   provision the monitoring-role stack in that same account.
3. Add a manual workflow with `id-token: write`, a pinned AWS credentials action,
   and the stack's role ARN. It needs no AWS access-key secrets.
4. Verify `get-caller-identity` returns the intended account and assumed role.
5. Add scoped SSM/artifact permissions in a later reviewed change, followed by a
   harmless SSM execution check. Secret retrieval permissions belong to the EC2 role.

Artifact-bucket selection and monitoring password migration are not prerequisites
for this authentication-only check. ECR publishing is not part of its acceptance.

References: [CfCT manifest targets](https://docs.aws.amazon.com/controltower/latest/userguide/cfct-manifest-file-resources-section.html),
[GitHub OIDC trust](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[CloudFormation OIDC provider](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-iam-oidcprovider.html),
[GetCallerIdentity](https://docs.aws.amazon.com/cli/latest/reference/sts/get-caller-identity.html).
