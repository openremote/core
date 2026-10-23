# Load1 on EKS

Deploy load1 with `test/or-eks-load`, which selects its profile and calls
`kubernetes/or-eks-cluster` and `kubernetes/or-eks-stack`.
Cluster creation and stack deployment are independent: use a dedicated load-test
cluster, or deploy another named stack into an existing compatible cluster.
The cluster template uses one `m8g.2xlarge` node. Component values preserve the
load1 resource budgets and provision separate 16 GiB Manager and PostgreSQL PVCs.

Run the commands below from the **repository root**.

## Creating load test manager image

Build from the repository root using the checkout you want to test. The current
setup build supports `SETUP_JAR=load1` directly; no Gradle file edits are needed.
The load profile uses the custom `load1` tag, so upgrading the Manager chart's
`appVersion` does not upgrade this image. Rebuild and push it for each Manager
version you want to test:

```
./gradlew -PSETUP_JAR=load1 clean installDist

# The Manager Dockerfile copies lib, but not deployment/manager/extensions.
# Include the custom setup JAR on the image's application classpath.
cp manager/build/install/manager/deployment/manager/extensions/openremote-load1-setup-*.jar \
    manager/build/install/manager/lib/

export AWS_DEVELOPERS_ACCOUNT_ID="dev-account-id"
aws ecr get-login-password --region eu-west-1 | docker login --username AWS --password-stdin $AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com
docker buildx build --push --platform linux/amd64,linux/arm64 -t $AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager:load1 manager/build/install/manager/
```

The profile uses `image.pullPolicy: Always` so new Manager Pods pull the current
`load1` image. Pushing the tag does not restart existing Pods. Keycloak and
PostgreSQL use the image versions in their Kubernetes charts.

The JAR copy above is required: without it, the image can initialize Keycloak
and allow login, but the custom load-test setup provider is absent and no test
users or assets are created. During a clean initialization, Manager logs should
identify `org.openremote.setup.load1.SetupTasks` as a custom setup provider.

## Deploy the cluster and stack

Copy the example configuration and edit its hostname, image repository, and
AWS profile settings:

```bash
mkdir -p .local
cp test/load1-eks/deployment.env.example .local/loadtest.env
```

Then deploy and retrieve credentials:

```bash
test/or-eks-load up --config .local/loadtest.env
test/or-eks-load credentials --config .local/loadtest.env
```

The [shared deployment guide](../README-eks-load.md) explains prerequisites,
configuration, verification, and cleanup. The Bash helper discovers the DNS
zone, configures the cluster's DNS role, and prepares component values
automatically. Use `cluster-up` and `deploy` separately when managing an
existing cluster. Selecting another profile does not resize existing nodes.

## Running tests

Use the clients and scripts in [load1](../load1/README.md), with
`MANAGER_HOSTNAME` set to this stack's hostname. The default deployment exposes
HTTPS on 443 and MQTTS on 8883 through the same HAProxy/NLB.

Before a load run, verify browser login, asset creation/editing, and an
authenticated MQTTS publish using a provisioned test account. On clean startup,
Manager logs should identify `org.openremote.setup.load1.SetupTasks`.

The `eks-*.sh` entry points now stop with migration guidance. They no longer
configure AWS credentials, provision static EBS volumes, or operate on the
current context's default namespace.
