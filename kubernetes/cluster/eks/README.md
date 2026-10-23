# EKS cluster management

`kubernetes/or-eks-cluster` manages the shared EKS cluster, native VPC CNI
NetworkPolicy enforcement, its shared AWS Load Balancer Controller and ALB
IngressClass, the `openremote-ebs` StorageClass, and optionally ExternalDNS. It
does not deploy or delete OpenRemote stacks, stack namespaces, certificates, or
stack data volumes. ExternalDNS changes records only for stack resources that
explicitly opt in.

After the shared cluster is ready, use `kubernetes/or-eks-stack` for the normal
stack workflow.

The current implementation uses the existing `kubernetes/cluster.yaml`
`eksctl` configuration. This boundary is intended to remain stable when EKS
infrastructure management moves from `eksctl` to CloudFormation.

## Configuration

The cluster name is deliberately required. The default AWS region is
`eu-west-1`; no named AWS profile is selected by the CLI.

```bash
export OR_EKS_CLUSTER_NAME=openremote-test
export OR_EKS_AWS_REGION=eu-west-1
```

Optional settings are:

```bash
export OR_EKS_CONFIG_FILE=/path/to/cluster.yaml
export OR_EKS_KUBE_CONTEXT=openremote-test@eu-west-1
export OR_EKS_LOAD_BALANCER_CONTROLLER_CHART_VERSION=1.14.0
export OR_EKS_SHARED_CERTIFICATE_ARN=arn:aws:acm:eu-west-1:210987654321:certificate/example
```

ExternalDNS is disabled when no DNS configuration is supplied. Its complete
managed configuration is:

```bash
export OR_EKS_EXTERNAL_DNS_HOSTED_ZONE_ID=Z0123456789
export OR_EKS_EXTERNAL_DNS_DOMAIN=example.com
export OR_EKS_EXTERNAL_DNS_ASSUME_ROLE_ARN=arn:aws:iam::123456789012:role/openremote-external-dns
export OR_EKS_EXTERNAL_DNS_OWNER_ID=210987654321-openremote-test-eu-west-1

# Optional
export OR_EKS_EXTERNAL_DNS_ASSUME_ROLE_EXTERNAL_ID=replace-with-a-random-value
export OR_EKS_EXTERNAL_DNS_IRSA_ROLE_NAME=openremote-openremote-test-eu-west-1-external-dns
export OR_EKS_EXTERNAL_DNS_CHART_VERSION=1.21.1
export OR_EKS_EXTERNAL_DNS_IMAGE_TAG=v0.22.0
```

The hosted-zone ID, domain, assumed role ARN, and owner ID form one atomic
configuration: if any is supplied, all four are required. Keep the owner ID
stable and unique among every ExternalDNS instance that can access the zone.
Changing it after records exist abandons the old TXT ownership records.

Command-line options override environment variables. Run
`kubernetes/or-eks-cluster --help` for the complete command reference.

The script does not write AWS credentials or profiles. Both EKS CLIs omit the
AWS/eksctl `--profile` argument by default, allowing the normal credential chain
to resolve credentials. Exported access keys and session tokens therefore work
without a named profile. Standard AWS environment settings remain inherited;
in particular, unset `AWS_PROFILE` and `AWS_DEFAULT_PROFILE` if you do not want
environment-based profile selection. Do not export `AWS_PROFILE=''`, which
AWS CLI can interpret as an empty profile name.

To select a named profile explicitly, pass `--profile <name>` to each EKS CLI
invocation. `--profile ''` is still accepted and has the same behavior as omitting
the option; it does not clear inherited AWS environment variables.

## Lifecycle

Run the examples in this section from the repository root.

### Create and inspect a cluster

Create a cluster and a stable kubeconfig context:

```bash
kubernetes/or-eks-cluster create \
  --name openremote-test \
  --region eu-west-1
```

Inspect it:

```bash
kubernetes/or-eks-cluster status --name openremote-test
```

### Reconcile cluster configuration

`apply` currently enables NetworkPolicy enforcement in the existing managed
VPC CNI configuration, reconciles the AWS Load Balancer Controller and its
CRDs, applies the shared `openremote-alb` IngressClass and `openremote-ebs`
StorageClass, and reconciles ExternalDNS when configured. It intentionally does
not attempt to mutate node-group infrastructure; that reconciliation will be
implemented by the future CloudFormation cluster definition.

```bash
kubernetes/or-eks-cluster apply --name openremote-test
```

The VPC CNI configuration update retains other configuration keys and does not
change the installed add-on version. If that version does not support native
NetworkPolicy, `apply` stops and asks for an explicit add-on update rather than
performing a version upgrade implicitly. New clusters select the latest
compatible VPC CNI version, give its dedicated IAM role the
`AmazonEKS_CNI_Policy`, and enable NetworkPolicy during creation.

For an existing managed add-on with a `serviceAccountRoleArn`, `apply` also
ensures that the `aws-node` ServiceAccount carries the matching IRSA annotation
before starting an add-on update. This prevents a VPC CNI rollout from losing
access to EC2 network-interface operations. If no dedicated add-on role exists,
the script warns that those permissions must instead come from the node IAM
role. Add-on waiter failures include the EKS health issue and current
`aws-node` rollout state.

See the AWS documentation for the current
[VPC CNI NetworkPolicy prerequisites and behavior](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy.html)
and [managed add-on configuration](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html).

### Destroy a cluster

Remove OpenRemote stacks and decide which data to retain before destroying the
cluster. Uninstalling workloads alone leaves namespaces, credentials, and PVCs,
which continue to block cluster destruction. Follow the
[stack cleanup instructions](../../README-AWS.md#uninstall-restore-or-destroy-a-stack)
and wait for external DNS and backing-volume cleanup to finish.

Cluster destruction requires an exact-name confirmation and is refused while
OpenRemote namespaces, Ingresses, LoadBalancer Services, PVCs, PersistentVolumes,
non-system Pods or workload controllers, namespaced `DNSEndpoint` resources,
or hostname reservations remain:

```bash
kubernetes/or-eks-cluster destroy \
  --name openremote-test \
  --confirm openremote-test
```

The shared ConfigMap `kube-system/openremote-hostnames` must contain no remaining
reservations. A reservation can outlive its namespace after interrupted external
cleanup. Finish `or-eks-stack destroy` or the documented
[manual hostname release](../../README.md#exclusive-public-hostnames) before
retrying cluster destruction; do not discard the registry to bypass this check.

The namespace check also protects internal stacks without public hostnames and
interrupted preparations. Workload checks include Deployments, StatefulSets,
DaemonSets, ReplicaSets, ReplicationControllers, Jobs, and CronJobs, even when
they have no Pods. Controllers in `kube-system`, `kube-public`, and
`kube-node-lease` are excluded, matching the Pod check. Empty unrelated
namespaces do not block deletion.

PersistentVolumes block deletion even after their PVCs have gone, so retained
storage or incomplete cleanup cannot silently lose its Kubernetes metadata.
The preflight lists blocking namespaces, controllers, and volumes. Inspect and
resolve them before retrying; for retained storage, preserve the data and
recovery information you need before removing its Kubernetes metadata. The
preflight does not delete these resources or discover orphaned AWS resources
that no longer have Kubernetes metadata.

After this preflight succeeds, deletion bypasses PodDisruptionBudgets while
draining the node group. This prevents replicated EKS system add-ons such as
CoreDNS, the EBS CSI controller, and metrics-server from blocking deletion once
all nodes have been cordoned. The bypass does not weaken the preflight for
OpenRemote stacks or other non-system workloads.

When the OpenRemote-managed ExternalDNS release exists, the preflight therefore
requires its Ingress and LoadBalancer Service sources to be gone before the
script uninstalls the controller. Pass `--skip-external-dns` only when its
lifecycle belongs to another operator.

### Kubernetes upgrades

Kubernetes minor-version upgrades are intentionally outside this first phase.
The current `cluster.yaml` uses a self-managed `nodeGroups` entry, so an upgrade
strategy must be designed separately before the cluster command exposes that
lifecycle operation.

## Shared ALB ingress

The `openremote-alb` IngressClass uses `IngressClassParams` to place all of its
Ingresses in one `openremote-stacks` group. This produces one internet-facing
ALB for the cluster by default and sends traffic directly to Pod IPs through
the Amazon VPC CNI. Its namespace selector accepts only namespaces labeled
`app.kubernetes.io/part-of=openremote`; `or-stack` applies that label when
preparing a stack namespace. The class enforces HTTPS redirection to port 443
for every group member.

The class defines shared routing behavior and can hold an optional shared
certificate ARN. Individual stacks define their hostnames, routes, certificate
selection, and DNS records. For stack deployment and certificate modes, see
[Shared HTTPS routing with Ingress](../../README-AWS.md#shared-https-routing-with-ingress).

See the controller's
[IngressClass documentation](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/ingress_class/)
for the enforced group and namespace-selector behavior.

### Configure a shared certificate

Supplying `--shared-certificate-arn` during create or apply records an
externally managed ACM certificate on `openremote-alb`. Later applies that omit
the option preserve the reference. `--clear-shared-certificate` removes only
the reference; neither command changes or deletes the certificate. Cluster
status reports the currently deployed value.

From the repository root, configure the shared certificate with:

```bash
kubernetes/or-eks-cluster apply \
  --name openremote-test \
  --region eu-west-1 \
  --shared-certificate-arn <acm-certificate-arn>
```

This configuration supplies a certificate for stacks that select `shared`
mode. Changing the reference does not change existing stacks' certificate
ownership modes, and cluster destruction does not delete the certificate.
For stack selection and certificate rotation, see
[Use a cluster-configured shared certificate](../../README-AWS.md#use-a-cluster-configured-shared-certificate).

## Automatic Route 53 records

The optional managed ExternalDNS installation is cluster-scoped. It watches
`Ingress`, `Service`, and namespaced `DNSEndpoint` resources, but filters all
of them by `openremote.io/managed-dns=true`. It uses only public Route 53 zones
and is restricted to the configured zone ID and domain. It uses TXT ownership
with a cluster-unique owner ID and the `sync` policy, so deleting a source
removes its owned record but not another controller's or an operator's records.
The chart and image are pinned independently; the current defaults are chart
`1.21.1` and ExternalDNS `v0.22.0`.

Hostnames managed by this controller must be strict subdomains of the configured
domain: for `example.com`, both `stack.example.com` and `staging.stack.example.com` qualify,
but `example.com` does not. Otherwise ExternalDNS's record-type prefix places
the ownership TXT name outside the domain allowed by the DNS role.

Cluster reconciliation explicitly applies the pinned chart's `DNSEndpoint`
CRD before installing ExternalDNS and waits for it to become established. The
chart grants the controller read/watch access to `DNSEndpoint` objects and
status-update access when its `crd` source is enabled. Cluster status reports
whether both the CRD and source are active.

For `Ingress` and `Service` sources, the controller supports the GA
`external-dns.kubernetes.io/hostname` annotation alongside the required
selection label. `DNSEndpoint` resources specify DNS records directly.

For stack deployment and automated DNS and HTTPS readiness checks, see
[Namespaced stacks](../../README-AWS.md#namespaced-stacks). For how stacks
declare DNS ownership on their resources, see
[DNS ownership](../../README.md#dns-ownership).

### Cross-account IAM bootstrap

Two short-lived credential chains remain separate:

```text
ExternalDNS Pod -> cluster-account IRSA role -> DNS-account Route 53 role
```

The cluster role may call only `sts:AssumeRole` on the configured DNS role. The
DNS role may change only `A`, `AAAA`, `CNAME`, and `TXT` records in one hosted
zone and only at or below one DNS suffix. No static AWS credentials are stored
in Kubernetes.

Create the DNS-account role with
[`external-dns-route53-role.yaml`](external-dns-route53-role.yaml). The role's
trust policy names the deterministic cluster role through an ARN condition, so
this CloudFormation stack can be deployed before the EKS cluster or IRSA role
exists. For example:

```bash
export CLUSTER_NAME=openremote-test
export CLUSTER_REGION=eu-west-1
export CLUSTER_AWS_PROFILE=or
export DNS_AWS_PROFILE=dns
export DNS_ZONE_ID=Z0123456789
export DNS_DOMAIN=example.com
export EXTERNAL_DNS_EXTERNAL_ID=replace-with-a-random-value
export EXTERNAL_DNS_IAM_STACK=openremote-test-external-dns

export CLUSTER_ACCOUNT_ID="$(aws sts get-caller-identity \
  --profile "$CLUSTER_AWS_PROFILE" \
  --query Account \
  --output text)"
export EXTERNAL_DNS_IRSA_ROLE_NAME="openremote-${CLUSTER_NAME}-${CLUSTER_REGION}-external-dns"

aws cloudformation deploy \
  --profile "$DNS_AWS_PROFILE" \
  --region "$CLUSTER_REGION" \
  --stack-name "$EXTERNAL_DNS_IAM_STACK" \
  --template-file external-dns-route53-role.yaml \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    HostedZoneId="$DNS_ZONE_ID" \
    DomainName="$DNS_DOMAIN" \
    ClusterAccountId="$CLUSTER_ACCOUNT_ID" \
    ClusterExternalDNSRoleName="$EXTERNAL_DNS_IRSA_ROLE_NAME" \
    ExternalId="$EXTERNAL_DNS_EXTERNAL_ID"

export EXTERNAL_DNS_ROLE_ARN="$(aws cloudformation describe-stacks \
  --profile "$DNS_AWS_PROFILE" \
  --region "$CLUSTER_REGION" \
  --stack-name "$EXTERNAL_DNS_IAM_STACK" \
  --query 'Stacks[0].Outputs[?OutputKey==`RoleArn`].OutputValue | [0]' \
  --output text)"
```

Run those commands from `kubernetes/cluster/eks`, or pass an absolute template
path. Supplying `RoleName` to the template is optional. If no external ID is
wanted, omit `ExternalId` from the parameter overrides and from the cluster
command.

Configure a new or existing cluster with the same source role name:

```bash
../../or-eks-cluster apply \
  --name "$CLUSTER_NAME" \
  --region "$CLUSTER_REGION" \
  --profile "$CLUSTER_AWS_PROFILE" \
  --external-dns-zone-id "$DNS_ZONE_ID" \
  --external-dns-domain "$DNS_DOMAIN" \
  --external-dns-role-arn "$EXTERNAL_DNS_ROLE_ARN" \
  --external-dns-external-id "$EXTERNAL_DNS_EXTERNAL_ID" \
  --external-dns-owner-id "${CLUSTER_ACCOUNT_ID}-${CLUSTER_NAME}-${CLUSTER_REGION}" \
  --external-dns-irsa-role-name "$EXTERNAL_DNS_IRSA_ROLE_NAME"
```

Use `create` instead of `apply` when the cluster does not exist. The DNS-account
CloudFormation stack must already exist so ExternalDNS can assume its output
role when Helm waits for the Deployment. `or-eks-cluster` creates or updates
the cluster-side role and `kube-system/external-dns` ServiceAccount through
eksctl, then installs the controller.

For a controller managed outside this command, pass `--skip-external-dns`.
`or-stack --dns external-dns` remains usable, provided that controller watches
Ingresses and Services, selects `openremote.io/managed-dns=true`, uses the GA
annotation prefix, and has a safe ownership registry. The skip option also
prevents `or-eks-cluster destroy` from explicitly uninstalling that Helm
release.

### Migrating existing DNS records

ExternalDNS does not silently adopt a manually created record. Migrate existing
records with this dry-run-first sequence:

1. Reconcile the cluster using the complete DNS configuration plus
   `--external-dns-dry-run`.
2. Reapply each intended stack using `or-stack apply` with its existing
   configuration and `--dns external-dns`. Use the portable command here because
   `or-eks-stack apply` rejects a controller running in dry-run mode.
3. Inspect the proposed changes with `kubectl --namespace kube-system logs
deployment/openremote-external-dns`.
4. Capture the exact current record values, then delete only the manually
   managed `A`, `AAAA`, or `CNAME` records for those stack hostnames. Do not
   delete ACM validation records or unrelated TXT records.
5. While still in dry-run, confirm that the logs propose creating the expected
   alias and TXT ownership records.
6. Run `or-eks-cluster apply` again with the same DNS configuration and without
   `--external-dns-dry-run`.
7. Verify the new alias and TXT records in Route 53 and test the endpoint.

### Monitoring and removing ExternalDNS

`or-eks-cluster status` reports the ExternalDNS Helm health, TXT owner ID,
dry-run state, and IRSA role. The controller's AWS provider and TXT registry are
documented in the upstream
[AWS tutorial](https://kubernetes-sigs.github.io/external-dns/latest/docs/tutorials/aws/)
and [registry documentation](https://kubernetes-sigs.github.io/external-dns/latest/docs/registry/registry/).

ExternalDNS waits for the ALB or NLB hostname in a source resource's status,
then creates the Route 53 alias and its TXT ownership record. When a source
resource or its DNS declaration is removed, the controller's event-triggered
sync removes only records carrying its owner ID.

Keep the controller running until its managed DNS records have been removed.
Wait for the public alias and TXT records to disappear before shutting down the
cluster or creating manual replacements. For stack and certificate cleanup,
follow the [stack lifecycle instructions](../../README-AWS.md#uninstall-restore-or-destroy-a-stack).
The [cluster destruction checks](#destroy-a-cluster) prevent controller removal
while managed source resources remain.

The DNS-account CloudFormation stack is deliberately not deleted with the
cluster. It can be retained for a recreated cluster using the same source role
name, or deleted explicitly in the DNS account after all managed records are
gone.

## Persistent storage

The `openremote-ebs` StorageClass uses the standard EBS CSI driver, encrypted
`gp3` volumes, and `WaitForFirstConsumer` binding. Its `Delete` reclaim policy
removes the EBS volume when its PVC is explicitly deleted. The component charts
retain their PVCs during an ordinary Helm uninstall, and cluster destruction is
blocked while any PVC or PersistentVolume remains.
