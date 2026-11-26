# Reusable staging plan workflow

[monitoring-plan.yml](../../.github/workflows/monitoring-plan.yml) packages the
collector with a customer-owned environment file, uploads one versioned S3 object,
and invokes the existing SSM deployment document with literal `Action=plan`.
It has no deploy input. It does not modify the application workflow or perform a cutover.

## Caller and prerequisites

Call the workflow from the customer repository, pinning its `uses` reference and
`core-revision` input to the same reviewed full core commit SHA. The two references
select the workflow implementation and the bundle source, respectively. Both must
be published in `openremote/core` before the caller is pushed. No PAT or infrastructure
checkout is needed: core is public, and the job token reads the caller checkout.

The calling job grants `contents: read` and `id-token: write`. It supplies:

| Input | Meaning |
| --- | --- |
| `core-revision` | Required full SHA for the collector bundle; match the workflow pin. |
| `aws-account-id` | Required expected customer account; checked against role and STS identity. |
| `compose-project` | Required independent monitoring project, matching the provisioned SSM document. |
| `configuration-file` | Relative non-secret caller file; defaults to `.ci_cd/monitoring/staging.env`. |
| `create-state` | Boolean, default false; plan creation of missing owned state on a first installation. |
| `enable-traces` | Boolean, default false; render and plan optional trace configuration. |

The job selects the caller repository's `monitoring-staging` GitHub environment.
Configure its deployment branch rules before running, allowing only reviewed branches.
The first rollout supports push and manual dispatch, not pull-request events.
The caller owns its triggers and branch condition. Concurrency is defined only in
the shared workflow; do not duplicate its group in the caller.

Set these non-secret variables in that environment:

- `MONITORING_AWS_ROLE_ARN`: dedicated `monitoring-staging` role in the expected account.
- `MONITORING_AWS_REGION`: region containing the instance and monitoring stacks.
- `MONITORING_EC2_INSTANCE_ID`: the reviewed target instance.
- `MONITORING_DEPLOY_DOCUMENT_NAME`: `openremote-monitoring-staging-deploy`.
- `MONITORING_DEPLOY_DOCUMENT_VERSION`: reviewed numeric document version, never `$LATEST`.

Provision the [AWS prerequisites](../aws/monitoring/README.md) and
[SSM deployment document](../aws/monitoring/deployment.md) first. The existing OIDC
subject remains `repo:<organization>/<customer-repository>:environment:monitoring-staging`.
Moving the called workflow to core does not change it to the core repository.
The target instance, data filesystem, secret ARN and monitoring project remain bound
in the provisioned document. Review those settings alongside the customer file.

## What a run does

1. Validate settings, check out the caller's run SHA and the pinned core SHA.
2. Copy only `deploy.py`, both Compose files, three Alloy fragments and the non-secret
   customer environment file. Render Compose without pulling or starting containers.
3. Assume the dedicated role through OIDC and verify the account/role again with STS.
4. Upload to `openremote-monitoring-<account>-<region>` under
   `staging/releases/plan-<run-id>-<attempt>/bundle.tar.gz`. Require an exact S3 version
   and record the SHA256, source revisions and object identifiers in the job summary.
5. Send one SSM plan command with SDK retries disabled for `SendCommand`. Wait and
   inspect that command's result; never resend automatically. The host wrapper validates
   the exact version, checksum and file layout before invoking the reviewed plan code.

A successful job requires both SSM success and the host wrapper's completion marker.
A blocked or unfinished plan fails the job. If delivery or the final result is uncertain,
inspect the command ID in SSM before retrying. The workflow never stops a collector,
removes a volume or invokes rollback. S3 artifacts remain available for review.

The plan reads host state; it does not retrieve passwords, pull images or change
containers. Bundle transfer still writes temporary host files, removed by the SSM
wrapper on normal completion/failure. This remains a privileged trusted-code channel:
`plan` is an operation in reviewed Python code, not an isolation boundary against a
malicious replacement script. Package signing is a separate future improvement.

An existing manually installed collector can legitimately block planning with
`Unmanaged project container; perform the agreed manual cutover first`. This proves
neither a successful deployment nor completion of the later host checks. Review the
exact output and agree the cutover before adding deployment capability.

## Validation

With Python, PyYAML 6.0.3 and `jq` available:

```sh
python3 -B .ci_cd/monitoring/tests/test_plan_workflow.py
```

Tests execute the actual workflow shell with fake AWS/Docker commands. They cover
input rejection, allowlisted bundle contents, identity/version checks and success,
blocked, unfinished or unavailable results without resending. They do not contact AWS.
The first customer workflow run still needs to verify GitHub environment/OIDC behavior,
public core checkout, versioned transfer and the host plan together.

References: [Reusable workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows),
[OIDC with reusable workflows](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-with-reusable-workflows).
