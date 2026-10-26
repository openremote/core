# Deploying EKS load-test stacks

`test/or-eks-load` is a Bash helper for deploying a load1 or load2 profile. Save
your settings in one file, then use `up` to configure DNS access, create or
reconcile the cluster, and deploy the stack. The default deployment provides
HTTPS and MQTTS through HAProxy, with a separate namespace and storage per stack.

Run the commands below from the **repository root**.

## Deploy and run a test

### 1. Prepare credentials and the Manager image

Install the tools required by the [EKS deployment guide](../kubernetes/README-AWS.md):
AWS CLI, eksctl, kubectl, Helm, jq, envsubst, dig, curl, and Python 3. The helper
itself uses Bash, including macOS's built-in Bash; the underlying EKS CLI uses
Python for its MQTTS TLS check.

Authenticate to the cluster account using your normal AWS credential chain.
For pasted temporary credentials, export `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`, and **unset** `AWS_PROFILE`
and `AWS_DEFAULT_PROFILE` if previously set. Do not set a profile to an empty
string. A named cluster profile can instead be selected in the config below.

Build and push the custom Manager image using the [load1](load1-eks/README.md)
or [load2](load2-eks/README.md) instructions. Ensure the cluster nodes can pull
it, including ECR repository permissions for cross-account pulls. The supplied
clusters use ARM nodes; the image examples publish both ARM64 and AMD64 images.
The helper does not build images or change registry permissions.

### 2. Save your deployment settings

For load2:

```bash
mkdir -p .local
cp test/load2-eks/deployment.env.example .local/loadtest.env
```

For load1, copy `test/load1-eks/deployment.env.example` instead.
Edit `.local/loadtest.env` for your deployment. For example:

```bash
LOAD_TEST=load2
LOAD_PROFILE=xlarge
CLUSTER_NAME=loadtest
STACK_NAME=load2
AWS_REGION=eu-west-1
LOAD_HOSTNAME=load2.openremote.app
LOAD_MANAGER_REPOSITORY=134517981306.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager
DNS_AWS_PROFILE=134517981306_AWSAdministratorAccess
```

`LOAD_MANAGER_REPOSITORY` includes `/openremote/manager`, without an image tag;
the profile selects `load1` or `load2`. `LOAD_HOSTNAME` is a bare hostname,
without `https://` or a path, and must be unused by another stack or cluster.

Use `DNS_AWS_PROFILE` for the account containing your public Route 53 zone.
Leave it empty to use the cluster credentials. If using an SSO profile,
authenticate it before deployment. Set `CLUSTER_AWS_PROFILE` only if you want
a specific named profile for cluster operations.

You do not normally need a zone ID, role ARN, external ID, or ownership ID.
The helper discovers the zone and configures a dedicated role for this cluster.
If multiple public zones match the same domain, it lists their IDs and stops;
add `DNS_ZONE_ID=...` to choose one explicitly.

The config is **sourced as Bash code**, so use only a file you trust. It needs
no `export` statements and must not contain access keys. `.local/` is ignored
by Git. Helper settings come from this file, replacing the old environment
variable and `--` forwarding interface.

### 3. Deploy

```bash
test/or-eks-load up --config .local/loadtest.env
```

This checks your credentials, configures DNS IAM, creates the cluster if absent
or reconciles its shared add-ons if present, and applies the selected profile.
It tests the ExternalDNS service account's actual IAM credential chain and
Route 53 read access before starting the stack deployment. It then waits for
DNS, trusted HTTPS, and MQTTS, and checks Keycloak's advertised login URL.

Initial dataset creation can take time; the default deployment timeout is
90 minutes. If credentials expire or a later deployment step fails, refresh
your credentials, fix the reported issue, and rerun the **same command**.
Reapplying retains stack data and credentials. Changing the selected profile
does not resize existing cluster nodes or upgrade Kubernetes.

### 4. Retrieve credentials and run the test

```bash
test/or-eks-load credentials --config .local/loadtest.env
test/or-eks-load status --config .local/loadtest.env
```

Open `https://load2.openremote.app/manager/` (substitute your configured hostname)
and sign in with the returned administrator credentials. Before running the
load scenario:

1. Check that the page is styled and login redirects stay on the correct host.
2. Verify the expected test users and assets, and asset creation/editing.
3. Verify an authenticated MQTTS publish using a provisioned test account.

Configure the test client with `MANAGER_HOSTNAME` set to `LOAD_HOSTNAME` and
MQTTS port **8883**. Plaintext MQTT is not exposed. Use the clients documented
in [load1](load1/README.md); for load2, use its
[scenarios and parameters](load2-eks/README.md#running-tests).

## Manage the deployment

All helper commands use the same `--config` file.

| Command                                    | What it does                                                                                           |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| `up`                                       | Reconcile DNS IAM and cluster add-ons, then deploy and verify the stack                                |
| `cluster-up`                               | Configure DNS IAM and the cluster only, then check DNS access                                          |
| `deploy`                                   | Deploy and verify a stack on an already configured cluster, without changing DNS IAM or shared add-ons |
| `status`                                   | Show stack status                                                                                      |
| `credentials`                              | Retrieve stack credentials                                                                             |
| `uninstall`                                | Stop the stack while retaining data, credentials, and certificate state                                |
| `destroy-stack --confirm <stack-name>`     | Delete one stack, its data, and its owned external resources                                           |
| `destroy-cluster --confirm <cluster-name>` | Delete a cluster after all its stacks have been destroyed                                              |
| `prepare --values-dir <new-directory>`     | Write editable profile values locally, without contacting AWS                                          |

Status, credentials, uninstall, and destruction use only cluster credentials.
`deploy` also uses only cluster credentials: it reads the installed ExternalDNS
configuration and tests its role access. `up` and `cluster-up` need permissions
to manage the dedicated IAM CloudFormation stack in the DNS account as well.

### Reuse a cluster or deploy independently

To deploy another stack in an already configured cluster, copy the deployment
config and choose a new `STACK_NAME` and `LOAD_HOSTNAME`. Keep the same
`CLUSTER_NAME` and region, check available capacity, then run:

```bash
test/or-eks-load deploy --config .local/another-stack.env
```

The existing cluster must have the OpenRemote add-ons and managed ExternalDNS.
For an independently sized cluster, choose a new `CLUSTER_NAME` too and use
`up`; the helper derives a separate DNS role and ownership ID.

### Customise component values

The standard profile requires no YAML copying. To change dataset parameters,
resource limits, or image tags, prepare an editable copy:

```bash
test/or-eks-load prepare --config .local/loadtest.env --values-dir .local/loadtest-values
```

This refuses to overwrite an existing directory. Edit the generated component
files, then add this line to `.local/loadtest.env`:

```bash
LOAD_VALUES_DIR=loadtest-values
```

`LOAD_VALUES_DIR` is relative to the **config file**, whereas `prepare`'s
`--values-dir` is relative to the current directory. Absolute paths also work.
With this setting, subsequent `up` and `deploy` calls use your files unchanged.
The image repository has already been rendered; edit `manager.yaml` to change
it. Remove the setting to return to the checked-in profile.

```bash
test/or-eks-load deploy --config .local/loadtest.env
```

An optional `or-setup.yaml` can supply further overrides. Prepared values can
also be passed directly to `or-eks-stack` or `or-stack` with `--values-dir`.

### Update the Manager image or inspect logs

Prefer a unique image tag per build and update `image.tag` in prepared
`manager.yaml`, then run `deploy`. If you replace the image under the same tag,
reapplying unchanged values does not restart Pods. Load the trusted config into
your shell and explicitly restart Manager:

```bash
source .local/loadtest.env
kubectl --context "$CLUSTER_NAME@$AWS_REGION" --namespace "$STACK_NAME" rollout restart deployment/manager
kubectl --context "$CLUSTER_NAME@$AWS_REGION" --namespace "$STACK_NAME" rollout status deployment/manager --timeout=90m
```

Use the same context and namespace to inspect setup logs:

```bash
kubectl --context "$CLUSTER_NAME@$AWS_REGION" --namespace "$STACK_NAME" logs deployment/manager --tail=100
```

On clean initialization, logs should identify `org.openremote.setup.load1.SetupTasks`
or `org.openremote.setup.load2.SetupTasks`. Load2 also provides internal JMX:

```bash
kubectl --context "$CLUSTER_NAME@$AWS_REGION" --namespace "$STACK_NAME" port-forward service/manager-jmx 8085:8085
```

### Reset the test dataset

Use prepared values and set `or.setupRunOnRestart: true` in `manager.yaml`.
Run `deploy` and wait for setup to finish, then set it back to `false` and run
`deploy` again before testing. **While enabled, this setting wipes the stack's
database on every Manager startup.** A normal reapply does not reset data.

### Delete resources

To stop the stack while retaining its data:

```bash
test/or-eks-load uninstall --config .local/loadtest.env
```

To permanently delete the example stack, then its now-empty cluster:

```bash
test/or-eks-load destroy-stack --config .local/loadtest.env --confirm load2
test/or-eks-load destroy-cluster --config .local/loadtest.env --confirm loadtest
```

Use the exact names from your config. Destroy **every** stack before deleting
a cluster; uninstall alone retains namespaces and is not enough. Cluster
destruction refuses remaining stacks/endpoints.

The DNS-account IAM CloudFormation stack is retained for reuse. Once the
cluster is gone and its DNS cleanup has completed, you can separately delete
`or-load-<cluster-account-id>-<cluster-name>-<region>-dns` in the DNS account.

## Configuration and deployment reference

### Optional settings

| Setting                 | Default / purpose                                                           |
| ----------------------- | --------------------------------------------------------------------------- |
| `CLUSTER_AWS_PROFILE`   | Empty: use the normal AWS credential chain                                  |
| `DNS_AWS_PROFILE`       | Empty: use cluster credentials                                              |
| `DNS_ZONE_ID`           | Discover the most specific matching public hosted zone; set to disambiguate |
| `DNS_DOMAIN`            | Discovered zone name; set to constrain discovery                            |
| `LOAD_VALUES_DIR`       | Render the selected profile into a temporary directory                      |
| `LOAD_TIMEOUT`          | `90m`; increase for longer initialization                                   |
| `LOAD_EXPOSURE`         | `haproxy`; `ingress` also supported                                         |
| `LOAD_MQTTS_HOSTNAME`   | Required for ingress; a separate hostname in the same managed domain        |
| `CERTIFICATE_ARN`       | Ingress only: use an existing web ACM certificate                           |
| `MQTTS_CERTIFICATE_ARN` | Ingress only: use an existing MQTTS ACM certificate                         |

Load1 has one fixed profile; omit `LOAD_PROFILE`. Load2 supports `large`
(the fallback when omitted), `xlarge-minimal`, `xlarge`, `2xlarge`, `4xlarge`,
and `8xlarge`. The example config selects `xlarge`.

### DNS configuration and retries

The DNS role grants access to the selected zone/domain and trusts the cluster's
exact ExternalDNS IRSA role. The helper uses a stable, account-qualified external
ID identifying that relationship; it is not an access key. No manually copied
role ARN or generated local state file is needed to resume on another machine.

On an existing cluster, `up` preserves ExternalDNS's TXT ownership ID and
refuses to change its managed zone/domain. It configures ExternalDNS to use the
helper's dedicated role, leaving any previously used DNS-account role intact.
Use `deploy` when the cluster's DNS IAM and add-ons should remain as configured.

The preflight requests a short-lived token for the `external-dns` service
account and tests both STS role assumptions and Route 53 reads. Your Kubernetes
identity needs permission to create that token. This catches trust/external-ID
mismatches early; DNS record writes and propagation are checked by deployment
readiness. Tokens and rendered temporary values are removed on exit, including
failure. Configuration and prepared values remain available for retries.

### Ingress with ACM

For a **new** stack, add these settings before running `up` or `deploy`:

```bash
LOAD_EXPOSURE=ingress
LOAD_MQTTS_HOSTNAME=mqtt-load2.openremote.app
```

The web endpoint uses a shared ALB; MQTTS uses a dedicated NLB and the separate
hostname. Set the MQTT scenarios' `MANAGER_HOSTNAME` to `LOAD_MQTTS_HOSTNAME`;
browser access still uses `LOAD_HOSTNAME`. The CLI uses a configured shared ACM
certificate if available, otherwise manages certificates for the stack. The
optional ARN settings select existing certificates instead.

Exposure and hostname changes require a new stack. This topology differs from
the legacy experiment, which puts both protocols behind one ACM-backed NLB.
For deployment modes outside this helper, use the
[EKS CLI](../kubernetes/README-AWS.md) or [or-stack](../kubernetes/README.md)
directly.

### Startup and capacity

Profiles contain the cluster template and component resource budgets. `up`
uses the template when creating a cluster; it does not resize existing nodes.
Manager and Keycloak use `Recreate` updates to avoid overlapping Pods on a
single-node cluster, so upgrades interrupt service.

If dataset initialization exceeds the Manager startup probe allowance, increase
that allowance in prepared `manager.yaml` as well as `LOAD_TIMEOUT`. A shared
cluster needs capacity for all stacks plus Kubernetes and shared controllers.
Namespace isolation does not remove CPU, disk, or network contention; use
separate clusters for independent benchmarks.

## Legacy scripts

The old load1 scripts and load2's standard `eks-setup-load.sh` remain in the
repository but stop with guidance directing users to the CLIs.
The [load2 ACM/NLB experiment](load2-eks/README.md#legacy-acmnlb-multi-proxy-experiment)
remains deployable using its existing setup, redeployment, and cleanup scripts.
Use a dedicated cluster for that workflow; do not mix it with CLI-managed stacks.

## Offline validation

```bash
python3 kubernetes/test/load-helper-test
python3 kubernetes/test/keycloak-url-test
python3 kubernetes/test/load-eks-test
```

The helper tests require Python 3, Bash, jq, and envsubst. They stub AWS,
Kubernetes, Helm, and the EKS CLIs to check all profiles, DNS discovery,
role-access failures, retries, config handling, and separate cleanup operations.
The Keycloak URL tests and profile tests also require Helm. The profile tests
render all seven profiles through the real `or-stack` and cover the retained
legacy ACM/NLB scripts. No AWS credentials or cluster are used.

Real EKS acceptance still requires login, dataset initialization, MQTTS traffic,
persistence, and independent stack cleanup against a deployed environment.
