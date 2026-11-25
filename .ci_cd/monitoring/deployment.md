# Host deployment

`deploy.py` is the small host entry point for the standalone collector. It uses
Python 3.9+ standard library, Docker/Compose, AWS CLI, findmnt and iproute2 on Linux.
Run it as root, as SSM will. Both Compose plugin and standalone forms are supported.
The script uses the local Docker socket and the EC2 instance role, ignoring inherited
AWS credential overrides and profile files.

**Local lifecycle validated.** All 13 fake-host tests and all nine disposable staging
Docker lifecycle checks passed. The existing collector remained ready and its
container lifecycle was unchanged. Central delivery and actual instance replacement
remain rollout checks. Review the staging cutover together before replacing a live
collector. The [SSM artifact download/execution wrapper](../aws/monitoring/deployment.md) is a
separate increment; the final customer deployment workflow remains to be integrated.

## Inputs and ordinary files

Use the trusted `docker-compose` directory from the selected core revision
and a customer-owned `customer.env`. Supply the independent project name explicitly;
`--project` is authoritative for Compose. Keep it distinct from the application project.
Select `--traces` only when the trace receiver settings and clients have been reviewed.

The default host layout is:

```text
/opt/openremote/monitoring/<project>/
  deploy.lock
  current -> releases/<last-successful-release>
  previous -> releases/<previous-successful-release>
  releases/<timestamp-and-random-suffix>/
    compose.yaml
    compose.traces.yaml       # only when enabled
    customer.env
    config/
    image.override.yaml      # resolved registry digest
    runtime.override.yaml    # ownership labels and absolute credential file paths
    credentials/
      loki_password
      metrics_password
```

Files are root-owned mode 0600; release/credential directories are private. The
runtime override takes precedence over the password file paths in `customer.env`.
The password values are not environment variables, command arguments or output.
The two JSON-formatted overrides are valid YAML usable by ordinary Compose commands.
There is no journal or separate image metadata database. Status reads Docker, not an
assumption that the `current` pointer describes what is running after a failed update.

The source image can be a version tag or a retained `image.override.yaml` containing
a digest. Deploy resolves/pulls it and saves the digest. Rollback uses that same digest and reuses it from the local cache when available;
otherwise it must pull that exact digest. It never substitutes a re-resolved tag.
Global image/volume pruning is outside the script.

## Plan and deploy

The source defaults to the directory containing `deploy.py`. From a trusted checkout:

```sh
sudo -n python3 .ci_cd/monitoring/deploy.py plan \
  --project alloy-customer-staging \
  --env-file /path/to/customer.env \
  --data-uuid <verified-data-filesystem-uuid> \
  --create-state
```

`plan` checks the data mount, project/volume ownership, settings and port conflicts.
It renders Compose in a temporary directory which is removed afterwards. It does
not pull images, fetch passwords, create volumes or modify installed releases. It
reports observed state and disk availability; it does not promise a no-change result
without resolving the image and comparing current secret contents. Review available
host memory and disk capacity before deployment as well; there is no automatic sizing.

`--create-state` authorizes creation of a missing volume only when there is no current
release or project container. Omit it for normal updates and retained-state rebuilds.
It never authorizes replacing an existing volume or initializing an unknown one.
A missing expected volume must be investigated, not silently replaced.

After review, use the same inputs with `deploy` and the environment's full secret ARN:

```sh
sudo -n python3 .ci_cd/monitoring/deploy.py deploy \
  --project alloy-customer-staging \
  --env-file /path/to/customer.env \
  --data-uuid <verified-data-filesystem-uuid> \
  --secret-arn <full-monitoring-secret-arn> \
  --create-state
```

Add `--traces` to both commands when intended. Only deploy/rollback acquire the
nonblocking installation lock. The sequence is: check mount/ownership/settings;
fetch and validate passwords; pull/pin image and prepare private files; validate
Alloy without network/host mounts; establish or check writable state; apply only
`alloy` through Compose and wait for local readiness/component health.

An identical rerun reuses the existing release paths, so ordinary Compose leaves a
running unchanged container intact. An explicitly requested deployment starts a
stopped collector or recreates a missing one. Changed configuration, image digest
or password contents selects a new release. Disable/skip deployment for intentional
maintenance stops. No application containers or networks are managed by this script.

## Status and explicit rollback

```sh
sudo -n python3 .ci_cd/monitoring/deploy.py status \
  --project alloy-customer-staging
```

Status reports actual container/image, local health, and retained release pointers.
Use the printed container ID with `sudo docker logs --tail 80 <container-id>` for
diagnostics. Local readiness and component health do not prove backend delivery;
verify fresh logs, host metrics and enabled traces centrally after deployment.

For a successful deployment that you want to undo, `rollback` defaults to `previous`:

```sh
sudo -n python3 .ci_cd/monitoring/deploy.py rollback \
  --project alloy-customer-staging \
  --data-uuid <verified-data-filesystem-uuid>
```

After a **failed update**, `current` still points to the last successful release.
Use `rollback --release /opt/openremote/monitoring/<project>/current` with the same
project/UUID arguments to restore that release. The failed attempt remains available
for inspection. A failed first install has no previous successful release; fix its
inputs and explicitly redeploy. Nothing attempts rollback automatically.

Rollback restores configuration/image/credentials, not volume history. Backend-revoked
passwords cannot be revived by rollback; redeploy the desired older configuration
with valid Secrets Manager credentials in that case. Confirm image/state compatibility
before downgrading Alloy. After a command timeout or interruption, inspect actual
status before retrying; Docker work may continue after its CLI exits.

## Persistence and remaining rollout work

The state volume has a stable explicit name and a monitoring project label. It must
use the local driver with no driver options, under the verified retained XFS mount.
New state is initialized once; existing state only gets a temporary write check,
never recursive ownership repair. A fresh boot disk with the same labeled retained
volume can be rebuilt through normal deploy without old local release metadata.

The manual staging collector/volume is deliberately not adopted. A foreign project
container, volume or occupied listener blocks deployment. Agree its manual removal,
new state choice, trace clients and canonical host-metric scrape cutover before rollout.

No automatic release cleanup is implemented in this first increment. Keep current,
previous and failed attempts for review; define bounded retention before production.
Only an allowlisted, non-secret resolved artifact should be retained externally:
Compose files, config fragments, customer.env and image.override.yaml. Never archive
a whole host release directory, which also contains passwords. The upcoming SSM and
workflow integration must implement that artifact handoff before production/rebuild
acceptance. Recreate credentials from Secrets Manager after boot-disk replacement.

## Local tests

```sh
python3 -B .ci_cd/monitoring/tests/test_deploy.py
```

These tests use temporary files and fake Docker/AWS responses. They cover install,
rerun/start, update, explicit rollback, missing state, reconstruction, invalid config,
failed health, ownership/port conflicts, trace addresses, secret shape and mount
validation. They do not prove real Compose recreation, image permissions or delivery.
Run the [disposable staging lifecycle check](tests/lifecycle.md) before touching
the existing collector. It exercises real Docker install/rerun/update/rollback and
retained-state reconstruction with dummy credentials.

References: [Compose up](https://docs.docker.com/reference/cli/docker/compose/up/),
[Alloy readiness and component health](https://grafana.com/docs/alloy/latest/reference/http/).
