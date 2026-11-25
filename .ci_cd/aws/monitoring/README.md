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

| Parameter | Value |
| --- | --- |
| `RoleName` | Dedicated monitoring role name, for example `monitoring-staging`. |
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
5. Add scoped SSM/artifact permissions in a later reviewed change, followed by a
   harmless SSM execution check. Secret retrieval permissions belong to the EC2 role.

Artifact-bucket selection and monitoring password migration are not prerequisites
for this authentication-only check. ECR publishing is not part of its acceptance.

References: [Manual workflow dispatch](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow),
[OIDC subjects](https://docs.github.com/en/actions/reference/security/oidc),
[CfCT manifest targets](https://docs.aws.amazon.com/controltower/latest/userguide/cfct-manifest-file-resources-section.html),
[GitHub OIDC trust](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[CloudFormation OIDC provider](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-iam-oidcprovider.html),
[GetCallerIdentity](https://docs.aws.amazon.com/cli/latest/reference/sts/get-caller-identity.html).
