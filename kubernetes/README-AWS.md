# Kubernetes deployment under AWS (EKS)

## TL;DR

Requirements: you need to have kubectl, helm, jq, curl, dig, Python 3 (for MQTTS readiness),
[aws cli](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html),
and [eksctl](https://docs.aws.amazon.com/eks/latest/userguide/install-kubectl.html#eksctl-install-update)
installed beforehand.

Cluster-only lifecycle management is available through
[`or-eks-cluster`](or-eks-cluster). It creates, inspects, reconciles shared
add-ons, and destroys the shared EKS cluster without deploying or deleting an
OpenRemote stack. See [`cluster/eks/README.md`](cluster/eks/README.md) for its
configuration and safety model.

[`or-eks-stack`](or-eks-stack) is the normal stack command for an EKS cluster
with OpenRemote-managed ExternalDNS. Its default HAProxy workflow coordinates
the stack NLB and ACME certificate. Its explicit Ingress workflow validates an
existing user-owned or cluster-configured shared ACM certificate, or creates
and retains a stack-owned managed ACM certificate, and coordinates the shared
ALB. Both delegate the Kubernetes installation to `or-stack`, wait for DNS to
point at the current load balancer, and verify the trusted Manager HTTPS
endpoint.

Both EKS CLIs use the normal AWS credential chain by default and do not pass a
profile argument to AWS or eksctl. For exported credentials, leave `AWS_PROFILE`
and `AWS_DEFAULT_PROFILE` unset and omit `--profile`. Select a named profile
explicitly with `--profile <name>`. Standard AWS environment configuration is
still inherited; an empty `AWS_PROFILE` is not the same as an unset variable.
The CLI-specific `OR_EKS_AWS_PROFILE` and `OR_EKS_STACK_AWS_PROFILE` environment
overrides are no longer used. See [credential configuration](cluster/eks/README.md#configuration)
for details, including refreshing existing kubeconfig contexts.

HAProxy stacks can additionally expose MQTTS on the same NLB, hostname, and
certificate with `--mqtts`. Ingress stacks use `--mqtts` with a separate
hostname and an existing user-owned, cluster-shared, or stack-managed ACM
certificate; `or-eks-stack` coordinates the certificate, dedicated MQTTS NLB,
ExternalDNS records, trusted endpoint readiness, and destruction. Plaintext
MQTT is not supported.

The `eks-setup*.sh` and `eks-cleanup*.sh` scripts below are the legacy combined
cluster-and-stack workflow. They will be split further as part of multi-stack
support and must not be used to remove one stack from a shared cluster.

The scripts have been tested within the openremote account.  
You must get proper credentials and set the AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_SESSION_TOKEN variable before running them.

The `eks-setup-haproxy.sh` script creates a new EKS cluster from scratch and deploys an OR stack in it.  
The `eks-cleanup-haproxy.sh` script deletes all the components that have been created by the above script.

The hostname is defined as an environment variable in the script, by default, it is testmanager.openremote.app

See comments within the script for more information.

## Setup

We support two public routing options: HAProxy exposed directly through a
per-stack NLB, or a private per-stack HAProxy gateway behind the shared ALB.
There are pros and cons to both approaches; see the [Networking](#networking)
section for more details.

We're creating an EKS cluster with a managed node group.  
By default, the group uses 2 VMs of type t2.large. The script places them in AZ eu-west-1a and eu-west-1b

### Hostname ownership

Each public hostname belongs exclusively to one stack in the cluster, regardless
of whether it uses HAProxy or shared ingress. The facade reserves it through
`or-stack prepare --hostname ...` before workloads or ACM requests, preventing
concurrent deployments from installing competing routes. Reapply preserves the
reservation; changing a stack's hostname is rejected. Uninstall and failed
operations retain ownership.

An AWS-aware orchestrator can reserve and verify namespace ownership before it
creates external resources:

```bash
./or-stack prepare \
  --name stack-a \
  --kube-context cluster@eu-west-1 \
  --target eks \
  --exposure ingress \
  --dns external-dns \
  --hostname stack-a.example.com
```

`or-eks-stack` performs this preparation automatically; normal deployments do not
need to run it separately.

`or-eks-stack destroy` releases a reservation after namespace and DNS cleanup
(and managed certificate cleanup where applicable). An interrupted cleanup can
be retried even when only the reservation remains. `or-eks-cluster destroy`
refuses outstanding reservations so their ownership and recovery information
are not lost.

`or-eks-stack destroy` requires a hostname reservation in every certificate
mode. Missing reservations stop destruction before resources are changed;
inspect the stack and registry rather than bypassing DNS cleanup. A namespace
left by a failed preparation that never acquired a reservation can be inspected
and removed with the portable `or-stack destroy` command.

For EKS stacks, the portable `or-stack destroy` command retains hostname
reservations after Kubernetes cleanup. Complete external cleanup using
`or-eks-stack destroy`, or verify that old public endpoints, DNS records, and
stack-managed certificates have been removed before explicitly releasing the
reservation with `or-stack release-hostname`.

See [exclusive public hostnames](README.md#exclusive-public-hostnames) for the
reason, registry permissions, required stack metadata, and explicit recovery
when external cleanup is performed manually. The registry is cluster-local;
coordination with other clusters and manually managed DNS remains external.

### Persistence

For persistence, the component charts request dynamically provisioned
[EBS volumes](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html).
`or-eks-cluster` reconciles the `openremote-ebs` StorageClass, which uses the
EBS CSI driver, encrypted `gp3` volumes, and `WaitForFirstConsumer` binding.
The EBS volume is therefore created in the availability zone selected for its
pod; no fixed volume IDs or pod node-affinity rules are required.

The EKS values files select `openremote-ebs`. Manager and PostgreSQL PVCs are
retained by an ordinary Helm uninstall. Explicitly deleting a retained PVC
deletes its dynamically provisioned EBS volume because this StorageClass uses
the `Delete` reclaim policy.

### Namespaced stacks

Use `or-eks-stack` for deployment with OpenRemote-managed DNS and HTTPS
readiness checks. The examples below run from the `kubernetes` directory.
Deploy a stack, retrieve its credentials, and use the lifecycle commands to
inspect, uninstall, or destroy it. For custom DNS or orchestration, see
[the advanced workflow](#advanced-deploy-with-or-stack-directly).

#### Before deploying

- Create and configure the shared cluster with `or-eks-cluster`. See the
  [cluster setup instructions](cluster/eks/README.md) for storage, networking,
  ExternalDNS, and cross-account DNS permissions.
- Configure AWS credentials with access to the EKS account and cluster. Both
  EKS CLIs use the normal AWS credential chain; add `--profile <name>` to select
  a named profile explicitly.
- Enable the OpenRemote-managed ExternalDNS release with dry-run disabled.
  Its domain filter must cover the hostname you intend to deploy.
- Choose a hostname strictly below the managed DNS domain. For example, with
  `openremote.app` configured, `test.openremote.app` and
  `staging.test.openremote.app` are supported, but `openremote.app` itself is not.
  Any certificate you supply must cover the chosen hostname.

`or-eks-stack` checks that the cluster is active, the selected kubeconfig
context points to it, and the managed ExternalDNS configuration is suitable.
Ingress deployments additionally require the shared `openremote-alb`
IngressClass and its enforced HTTPS redirect.

#### Deploy with HAProxy (default)

After creating and configuring the shared cluster, the normal managed-DNS
HAProxy deployment is one command. HAProxy is the default exposure and creates
one internet-facing NLB per stack:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --hostname stack-a.example.com
```

The command installs the stack, waits for DNS to point to its NLB, obtains or
reuses the HAProxy certificate, and waits for a trusted HTTPS response from
Manager. After it succeeds, open `https://stack-a.example.com/manager/` and
[retrieve the Manager credentials](#inspect-a-stack-and-retrieve-credentials).

Enable MQTTS on the same hostname and NLB with:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --hostname stack-a.example.com \
  --mqtts
```

This adds public TCP port 8883 to the existing proxy Service. Use
`--mqtts-port <port>` only when a different public port is required. Repeat
`--mqtts` on later applies that should retain the MQTTS exposure.

The automated HTTP-01 certificate flow requires public HTTP port 80. The
default timeout is 20 minutes for each readiness phase. Reapply the same command
to update the stack; retained certificates are reused. To deploy another stack,
choose a different stack name and hostname.

#### Shared HTTPS routing with Ingress

Select `--exposure ingress` to route web traffic through the shared OpenRemote
ALB and a private HAProxy gateway in each stack namespace. TLS terminates at the
ALB using an ACM certificate. `or-eks-stack apply` handles certificate validation,
DNS readiness, and the trusted Manager HTTPS check.

Choose the certificate mode before deploying:

| Mode       | Certificate source                                                   | Effect of stack destruction                         |
| ---------- | -------------------------------------------------------------------- | --------------------------------------------------- |
| `managed`  | The stack requests and owns an ACM certificate.                      | Deletes the certificate and its validation records. |
| `existing` | You supply an externally managed certificate ARN.                    | Leaves the certificate unchanged.                   |
| `shared`   | The stack uses the externally managed ARN configured on the cluster. | Leaves the certificate unchanged.                   |

On the first apply, omitting `--certificate-mode` selects `shared` if the
cluster has a shared certificate configured, otherwise `managed`. On later
applies, the recorded mode is reused. The examples below select each mode
explicitly.

##### Create a stack-owned certificate

With `managed`, certificate issuance completes before workloads are installed.
ACM DNS validation can use the configured DNS account even when it is separate
from the EKS account.

Managed mode assumes that both the certificate and its ACM DNS validation
records are exclusive to the stack. Other resources must not use the certificate,
and other certificates must not depend on its validation records. ACM can reuse
a validation CNAME for separate certificates, including certificates in other
regions of the same AWS account. This exclusivity is a deployment requirement;
the script's ownership checks do not establish it. Destroying the stack removes
both its certificate and validation records.

The resolved AWS identity needs `acm:RequestCertificate`,
`acm:ListCertificates`, `acm:DescribeCertificate`, and
`acm:ListTagsForCertificate` in the EKS account. It also needs
`acm:DeleteCertificate` when destroying a stack with either managed endpoint, in
addition to the EKS access already required by the facade. Route 53 write
permission remains confined to the ExternalDNS roles; `or-eks-stack` does not
use DNS-account credentials.

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode managed
```

##### Use an existing certificate

Use `existing` with an ACM certificate in the EKS account and region. It must
have status `ISSUED`, cover the hostname with an exact name or one-level
wildcard, and be externally managed rather than owned by `or-eks-stack`.
The command checks these requirements before changing the stack and rejects
certificates carrying the `openremote.io/managed-by=or-eks-stack` ownership tag.

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode existing \
  --certificate-arn arn:aws:acm:eu-west-1:123456789012:certificate/example
```

The certificate remains user-owned: apply only references it, while uninstall
and destroy never modify or delete it. The selected mode and ARN are shown by
`or-eks-stack status`.

##### Use a cluster-configured shared certificate

An externally managed certificate intended for multiple stacks can instead be
recorded once as cluster configuration:

```bash
./or-eks-cluster apply \
  --name <cluster-name> \
  --region eu-west-1 \
  --shared-certificate-arn arn:aws:acm:eu-west-1:123456789012:certificate/example
```

This option records an externally managed ARN on the `openremote-alb`
IngressClass for stacks to use; it does not create, tag, renew, or delete the
certificate. Omitting it on a later cluster apply preserves the reference.
Use `--clear-shared-certificate` to remove the reference without changing ACM.

Stacks then select it without repeating the ARN:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode shared
```

Each stack validates the shared certificate's account, region, `ISSUED` status,
hostname coverage, and ownership before changing resources. Uninstall and
destroy never modify or delete the certificate.

##### Reapply or rotate certificates

Certificate mode is a persistent stack setting. When omitted on later applies,
the recorded mode is reused, even if the cluster's shared certificate configuration
has changed. An explicit different mode is rejected before modifying resources.
A stack recorded as `shared` fails clearly if the cluster's shared certificate
configuration is removed; it does not fall back to `managed`.

In-place certificate mode migration is not supported. To change modes, back up
any data you need to retain, destroy the stack and finish its cleanup, then
recreate it in the desired mode. This avoids abandoning a managed certificate
and its validation records while the Ingress switches to another certificate.

Certificate rotation within a mode remains supported. Mode `existing` reuses
its recorded ARN when omitted; supply `--certificate-mode existing` and a new
`--certificate-arn` to replace it. Mode `shared` uses the cluster's current shared
ARN on each apply. Mode `managed` reuses its owned certificate.

#### MQTTS with Ingress

Enable MQTTS for an Ingress stack with a second hostname. Its certificate mode
is independent of the web certificate mode: either endpoint can use
`existing`, `shared`, or `managed`. To select a dedicated stack-managed MQTTS
certificate explicitly:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode existing \
  --certificate-arn arn:aws:acm:eu-west-1:123456789012:certificate/web \
  --mqtts \
  --mqtts-hostname mqtt-stack-a.example.com \
  --mqtts-certificate-mode managed
```

The MQTTS hostname must differ from the web hostname because it points to a
dedicated per-stack NLB rather than the shared web ALB. Managed mode records its
intent before requesting ACM, requests or recovers one exactly tagged
certificate for the MQTTS endpoint, publishes ACM's validation CNAME through
the namespace's `acm-validation-mqtts` `DNSEndpoint`, and installs workloads
only after ACM reports `ISSUED`.

If neither an MQTTS mode nor ARN is supplied on first apply, the facade uses
the cluster certificate configured by `or-eks-cluster
--shared-certificate-arn`. It validates that the shared certificate also covers
the MQTTS hostname, attaches the same ARN to the stack's dedicated NLB, and
never deletes it. If the cluster has no shared ARN, selection falls back to
managed mode. Shared mode can also be requested explicitly:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode shared \
  --mqtts \
  --mqtts-hostname mqtt-stack-a.example.com \
  --mqtts-certificate-mode shared
```

To supply a user-owned certificate instead, use:

```bash
./or-eks-stack apply \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --exposure ingress \
  --hostname stack-a.example.com \
  --certificate-mode existing \
  --certificate-arn arn:aws:acm:eu-west-1:123456789012:certificate/web \
  --mqtts \
  --mqtts-hostname mqtt-stack-a.example.com \
  --mqtts-certificate-mode existing \
  --mqtts-certificate-arn arn:aws:acm:eu-west-1:123456789012:certificate/mqtts
```

An explicit MQTTS ARN also implies `existing`, so its mode option may be
omitted. The certificate must be in the EKS account and region, have status
`ISSUED`, and cover the MQTTS hostname. It must be externally owned:
certificates tagged as managed by `or-eks-stack` are rejected, and the facade
never renews or deletes it. A single wildcard or multi-name existing
certificate may be supplied for both web and MQTTS when it meets the ownership
and hostname checks.

Repeat `--mqtts` on every reapply. After first selection, the MQTTS mode is
persistent and the hostname and certificate ARN may be omitted because they are
read from namespace annotations. An explicit different mode is rejected.
Existing mode can rotate its certificate reference by supplying a new ARN;
shared mode follows the cluster's current shared ARN; managed mode reuses its
owned certificate. Changing or disabling the MQTTS hostname in place is not
supported. To change mode or hostname, back up any data to retain, destroy the
stack, and recreate it. `or-eks-stack status` reports the recorded MQTTS
hostname, certificate mode, ARN, and current ACM status.

Apply waits for both the shared ALB and dedicated MQTTS NLB, verifies each DNS
name points to its current load balancer, and verifies trusted HTTPS and MQTTS.
Uninstall retains both endpoint reservations and the recorded certificate
state. Destroy validates the MQTTS Service and its lifecycle metadata, removes
the namespace, confirms the NLB and both public DNS names are gone, and then
releases both reservations. Existing and shared MQTTS certificates are always
retained. NLB deletion is checked using the controller's AWS identity tags:
`elbv2.k8s.aws/cluster=<cluster>` and `service.k8s.aws/stack=<stack>/proxy-mqtts`.
This check does not depend on the Service's last reported address and also
runs when the Service or namespace is already gone, so an interrupted destroy
or an earlier uninstall cannot bypass it. AWS lookup failures retain both
reservations; retry destroy once the lookup succeeds and the NLB is deleted.
For managed mode it also waits for NLB detachment and
validation-CNAME removal, then deletes only the certificate carrying the exact
cluster, stack, hostname, and `openremote.io/endpoint=mqtts` ownership tags.
Apply records certificate mode before ACM requests or Helm installation. If it
stops earlier, between hostname reservation and recording mode, destroy also
accepts the incomplete preparation when no MQTTS Service, recorded certificate
ARN, or validation DNSEndpoint exists. The hostname, if already recorded, must
match its reservation. Normal certificate ownership, NLB deletion, and DNS
cleanup checks still apply before releasing either hostname. A low-level
`or-stack` MQTTS endpoint with deployed resources is not adopted automatically
while its namespace still exists.

Destroying an Ingress stack with MQTTS also uses
`elasticloadbalancing:DescribeLoadBalancers` and
`elasticloadbalancing:DescribeTags` to confirm deletion of the dedicated NLB,
including on retries after namespace deletion.

#### Inspect a stack and retrieve credentials

These commands apply to both HAProxy and Ingress stacks. Inspect the stack's
resources and configuration with:

```bash
./or-eks-stack status \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1
```

New stacks receive independently generated Manager and PostgreSQL passwords.
Retrieve a stack's Manager administrator login without exposing its database
credentials with:

```bash
./or-eks-stack credentials \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1
```

#### Uninstall, restore, or destroy a stack

##### Uninstall and restore

Remove workloads while retaining their credentials and EBS-backed data with:

```bash
./or-eks-stack uninstall \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1
```

Reapplying the stack with the same target and exposure reuses its retained
Secret and data PVCs. HAProxy exposure also reuses its retained certificate
PVC; Ingress gateways have no certificate PVC because TLS terminates at the
ALB. The selected target and exposure are stored as namespace labels; changing
either is rejected until a deliberate migration workflow is implemented.

For managed Ingress certificates, uninstall also retains the certificate and
its DNS validation records so ACM can renew it and a later apply can reuse it.
Restore workloads by rerunning the original `apply` command.

##### Destroy the stack and its data

Use `destroy` to delete the namespace, credentials, PVCs, and dynamically
provisioned EBS volumes. This command applies to HAProxy and all Ingress
certificate modes. Managed Ingress certificates and their validation records
are also deleted; existing and shared certificates are preserved.

```bash
./or-eks-stack destroy \
  --name stack-a \
  --cluster <cluster-name> \
  --region eu-west-1 \
  --confirm stack-a
```

Destruction requires exact-name confirmation, matching stack ownership, and a
hostname reservation. It stops if certificate or DNS ownership is absent,
mismatched, or ambiguous. It waits for external DNS cleanup and for the
`Delete` reclaim policy to remove the stack's PersistentVolumes. See
[hostname ownership](#hostname-ownership) for recovery from incomplete preparation.

An interrupted destroy can be retried, including after the namespace has gone.
For managed certificates, the command recovers ownership from the retained
reservation and certificate tags. The
[managed-certificate lifecycle](#managed-certificate-lifecycle) explains the
cleanup order.

Complete stack cleanup before running `or-eks-cluster destroy`. Cluster
destruction refuses remaining OpenRemote namespaces, non-system workload
controllers (even those with no Pods), retained hostname reservations, and
PersistentVolumes whose claims have already gone. See the
[cluster lifecycle documentation](cluster/eks/README.md#lifecycle).

#### Advanced: deploy with `or-stack` directly

Use the portable `or-stack` command for custom DNS ownership, internal-only
stacks, or custom orchestration. It installs Kubernetes resources but makes no
AWS calls and does not perform the end-to-end DNS, certificate, and HTTPS
readiness workflow of `or-eks-stack`.

##### HAProxy with externally managed DNS

Use this procedure when you manage DNS records yourself. `or-stack` deploys the
Kubernetes resources; you then configure DNS, complete certificate setup, and
verify HTTPS access.

1. **Deploy the stack without DNS automation.**

   ```bash
   ./or-stack apply \
     --name stack-a \
     --kube-context <cluster-name>@eu-west-1 \
     --target eks \
     --hostname stack-a.example.com \
     --dns none
   ```

2. **Point the hostname at the stack's load balancer.** Retrieve the NLB hostname:

   ```bash
   kubectl --context <cluster-name>@eu-west-1 \
     --namespace stack-a \
     get service proxy \
     -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}'
   ```

   If the output is empty, wait for the load balancer to be provisioned and
   retry. Use your DNS provider to point `stack-a.example.com` at that hostname.
   Wait until public DNS resolves to the current load balancer before continuing.

3. **Complete certificate setup.** The HTTP-01 challenge requires public HTTP
   port 80 to reach HAProxy. If the hostname already has retained Certbot
   certificate state, skip the following command and reuse it. Otherwise,
   request the certificate after DNS is ready:

   ```bash
   kubectl --context <cluster-name>@eu-west-1 \
     --namespace stack-a \
     exec deployment/proxy \
     --container proxy \
     -- /entrypoint.sh add stack-a.example.com
   ```

   If you intentionally use a custom public HTTP port, arrange a suitable
   certificate separately instead of relying on this HTTP-01 procedure.

4. **Verify certificate status and Manager access.** Inspect the certificate:

   ```bash
   ./or-stack status \
     --name stack-a \
     --kube-context <cluster-name>@eu-west-1
   ```

   Then open `https://stack-a.example.com/manager/` and confirm that Manager is
   reachable over trusted HTTPS. Certificate files alone do not establish that
   the public endpoint works.

##### Alternative: let ExternalDNS manage the HAProxy record

If your cluster has a compatible ExternalDNS controller, use
`--dns external-dns` instead of `--dns none` in the apply command above. The
proxy Service declares the hostname, and the controller creates the DNS record
pointing to the NLB. Wait for DNS to resolve to the current load balancer, then
complete the certificate and HTTPS checks in steps 3 and 4 yourself.

##### Ingress with externally managed readiness

Use `or-stack --exposure ingress` directly when DNS or endpoint readiness is
managed elsewhere. The portable command accepts the ARN but deliberately does
not inspect ACM or call Route 53.

With this workflow, you are responsible for supplying a suitable issued ACM
certificate, arranging DNS, and checking the public HTTPS endpoint. Inspect the
ALB address once the controller has published it:

```bash
kubectl --context <cluster-name>@eu-west-1 \
  --namespace stack-a \
  get ingress proxy \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}'
```

##### Ingress MQTTS with externally managed readiness

The HTTP Ingress does not carry MQTT traffic. On EKS, `or-stack` can create a
separate per-stack NLB for MQTTS while retaining the same private proxy gateway:

```bash
./or-stack apply \
  --name stack-a \
  --kube-context <cluster>@eu-west-1 \
  --target eks \
  --exposure ingress \
  --dns external-dns \
  --hostname stack-a.example.com \
  --certificate-arn <web-acm-certificate-arn> \
  --mqtts \
  --mqtts-hostname mqtt-stack-a.example.com \
  --mqtts-certificate-arn <mqtts-acm-certificate-arn>
```

The dedicated `proxy-mqtts` LoadBalancer Service creates an IP-target NLB. ACM
terminates TLS on public port 8883, then the NLB forwards plaintext TCP to port
1883 on the namespace-local proxy, which forwards it to Manager. The MQTTS
hostname must differ from the web hostname because their DNS records target
different load balancers. `--mqtts-port` changes the public NLB listener port.

This low-level workflow only declares the provided certificate ARN; it does not
inspect, create, renew, or delete the certificate. When `--dns external-dns` is
selected, it declares the MQTTS hostname on the NLB Service. Without it, DNS is
also the caller's responsibility.

For managed DNS, certificate lifecycle, and endpoint readiness, use
[MQTTS with Ingress](#mqtts-with-ingress) through `or-eks-stack`.

##### Internal-only stacks

Use `--exposure none` with `or-stack` for an internal-only EKS stack. It creates
no proxy, Ingress, ALB, or NLB:

```bash
./or-stack apply \
  --name stack-internal \
  --kube-context <cluster-name>@eu-west-1 \
  --target eks \
  --exposure none
```

##### Use a separate ALB group

A stack can opt out of the default group through its `proxy.yaml` values. Set
`ingress.className: alb` and give the proxy Ingress a unique
`alb.ingress.kubernetes.io/group.name`, together with the desired `scheme` and
`target-type` annotations. Also add the `ssl-redirect` annotation because the
dedicated class does not inherit the shared class's redirect setting. That
creates a separate ALB group for that stack; the shared class remains the
default.

#### How deployment works

The following details explain the validation, routing, and recovery behavior
behind the commands above. They do not add manual steps to the normal
`or-eks-stack` workflow.

##### Cluster checks, storage, and network isolation

The EKS target verifies that the EBS CSI driver and `openremote-ebs`
StorageClass exist. It also requires the Amazon VPC CNI NetworkPolicy
`PolicyEndpoint` CRD and node agent, confirming that native policy support is
configured. Public HAProxy and Ingress exposure also require the AWS Load
Balancer Controller, while `--exposure none` does not. The target selects
encrypted dynamically provisioned EBS storage for Manager and PostgreSQL.
HAProxy exposure additionally persists its certificate state; the private
Ingress gateway does not store certificates.

Each stack applies an ingress NetworkPolicy that allows traffic from its own
namespace and rejects traffic originating in other stack namespaces. Egress is
not restricted. In HAProxy mode, a second policy permits external traffic only
to the HAProxy Pod's HTTP and HTTPS ports, plus its MQTTS port when enabled;
Manager, Keycloak, PostgreSQL, and Manager's plaintext MQTT listener are not
directly public. In Ingress mode, the second policy permits the ALB to reach
HTTP port 8080 on a private per-stack HAProxy gateway. When low-level Ingress
MQTTS is enabled, it also permits the dedicated NLB to reach the gateway's
plaintext port 1883. That gateway then reaches Manager and Keycloak through the
same-namespace rule, so neither application Pod is directly reachable from
another stack namespace.
`or-eks-cluster create` enables NetworkPolicy on the managed VPC CNI add-on,
and `or-eks-cluster apply` reconciles that setting without changing the
installed add-on version. New clusters give the VPC CNI add-on a dedicated IAM
role with `AmazonEKS_CNI_Policy`; for existing add-ons, cluster reconciliation
aligns the `aws-node` ServiceAccount annotation with the role already recorded
by EKS.

##### DNS domain restrictions

The hostname must be below the managed domain because ExternalDNS prefixes
its ownership TXT names with a record type. At the managed domain itself, these
records would fall outside the IAM domain boundary. Subdomain depth is
unrestricted by this check, but the certificate must still cover the hostname.

##### HAProxy certificate lifecycle and readiness

In HAProxy exposure mode, the proxy manages its own TLS certificate and keeps
Certbot account and certificate data on its retained `proxy` PVC.

`or-eks-stack` automates the necessary ordering. It waits until the public
hostname and the current NLB hostname resolve to at least one common address,
so a stale record from an older deployment is not considered ready. It then
checks the proxy's persisted Certbot state and triggers `/entrypoint.sh add`
only when the hostname has no certificate lineage. Existing private-key and
full-chain files are reused; normal renewal remains the proxy's responsibility.
Incomplete state or a failed inspection stops apply for investigation. If proxy
startup creates the certificate concurrently and `add` fails, the facade
rechecks the files before continuing.

For manual certificate operations, `add` rejects an existing certificate
lineage, while `renew` forces issuance rather than checking readiness.

`or-eks-stack` independently waits for a trusted response from the canonical
`/manager/` URL: persisted files alone do not prove that TLS or Manager is ready.
A Manager error or connection timeout therefore does not trigger certificate
issuance or forced renewal.

When `--mqtts` is enabled, it also completes a trusted TLS handshake with the
MQTTS listener before reporting the stack ready. HTTPS and MQTTS use the same
hostname and HAProxy certificate.

The proxy readiness probe and certificate status are deliberately separate.
The Pod must be ready and reachable for the HTTP ACME challenge before a
production certificate can be issued; `or-stack status` shows the certificate
issuer and expiry once managed or custom certificate material is present.

##### MQTTS readiness

MQTTS readiness requires Python 3 with its standard `ssl` module and a working
CA trust store (using Python's default CA paths or `SSL_CERT_FILE`/`SSL_CERT_DIR`).
The probe verifies the certificate chain and requested hostname
through the TLS API: OpenSSL/LibreSSL diagnostic output alone can report a zero
verification code even when no TLS session was established. Each probe is
limited to ten seconds or the remaining readiness timeout, whichever is shorter.
This verifies TLS readiness; MQTT authentication and publish/subscribe behavior
still require an MQTT client test.

##### Managed-certificate lifecycle

This lifecycle applies independently to managed web and MQTTS certificates
on Ingress stacks. Namespace annotations record each endpoint's selected mode
and certificate ARN. ACM tags identify ownership by deployment tool, cluster,
stack, exact hostname, and endpoint, allowing the CLI to verify ownership and
recover interrupted operations. Certificates created before endpoint tagging
remain recognized as web certificates for upgrade compatibility.

###### Apply

`or-eks-stack apply` performs these steps automatically:

1. Prepare the namespace and record the certificate mode before making ACM
   requests or installing workloads.
2. Reuse the recorded certificate ARN, discover a uniquely tagged certificate,
   or request a new DNS-validated ACM certificate if none exists.
3. Store validation CNAME records in the namespace's `acm-validation`
   `DNSEndpoint` for web certificates and `acm-validation-mqtts` for MQTTS.
   The latter certificate carries the `openremote.io/endpoint=mqtts` tag.
   ExternalDNS publishes the records through its restricted cross-account role.
4. Wait for every selected managed certificate to report `ISSUED` before
   installing workloads.

Both endpoints verify ownership before updating an existing validation
`DNSEndpoint`. Only a confirmed missing resource permits creation; permission
errors or API failures stop apply before writing validation records.

###### Retry after interruption

Rerun the same `apply` command. The recorded mode survives failed applies; if
the certificate ARN was not recorded before the interruption, the command
discovers the certificate through its ownership tags or requests one if none
exists.

A conditional namespace update prevents concurrent first applies from choosing
different modes. If that update conflicts, retry `apply`.

###### Uninstall

`uninstall` retains the recorded mode, certificate, and validation records.
ACM can continue renewing the certificate, and a later `apply` can reuse it.

###### Destroy

Before deleting anything, `destroy` validates the namespace, Ingress, MQTTS
Service when present, each validation `DNSEndpoint`, and exact ACM ownership
tags. It then:

1. Removes the Ingress and waits for the web hostname to disappear from
   authoritative DNS and for the managed web certificate to be detached.
2. Destroys the namespace, including the MQTTS Service and validation resources.
3. For MQTTS, waits for NLB deletion and managed-certificate detachment.
4. Waits for public and validation DNS records to disappear from authoritative
   DNS.
5. Deletes the owned managed certificates.

An interrupted `destroy` can be retried. The command can recover the certificate
from its cluster, stack, and endpoint tags even after the namespace is gone. Existing and
shared certificates are never deleted by stack destruction.

##### Shared ALB routing and gateway design

`or-eks-cluster` creates an `openremote-alb` IngressClass backed by an AWS Load
Balancer Controller `IngressClassParams` resource. It fixes the scheme to
`internet-facing`, uses VPC CNI Pod IPs as targets, and puts every matching
Ingress in the `openremote-stacks` group. It also enforces redirection from
HTTP to HTTPS. The controller therefore merges one proxy Ingress from each
stack namespace onto one ALB by default.

The resulting web path is:

```text
Client -> shared ALB (TLS/ACM) -> stack HAProxy gateway (HTTP) -> Manager/Keycloak
```

TLS terminates at the ALB. HAProxy receives plain HTTP and routes /auth to Keycloak and other paths to Manager.
It does not request certificates or expose an HTTPS Service port in this mode.

The gateway is an intentional NetworkPolicy boundary. The external ALB has no
Kubernetes Pod or namespace labels, so a portable NetworkPolicy rule that lets
it target Manager and Keycloak directly also admits Pods from other namespaces
on those ports. Opening only the proxy port preserves direct cross-stack
isolation.

This design adds one internal HTTP hop and one small proxy Pod per stack. The
reason to select it is ALB-managed web TLS and layer-7 integration: ACM, SNI,
central HTTP redirects, and optional AWS features such as WAF. It does not
reduce the final load-balancer count. MQTTS requires one additional NLB per
Ingress stack, while HAProxy exposure reuses its existing per-stack NLB.

Only namespaces carrying `app.kubernetes.io/part-of=openremote` may use this
class. `or-stack` applies that label when it creates or reapplies a valid stack
namespace. This cluster-side restriction prevents unrelated namespaces from
joining the shared ALB merely by naming its group.

The stack Ingress declares its hostname and the cluster controller creates the
record that points to the ALB. The facade waits for the Ingress to publish its
ALB hostname, confirms that public DNS resolves to that current ALB, and then
verifies the trusted `/manager/` endpoint. The EKS target configures listeners
on ports 80 and 443, while `or-stack` adds the validated certificate supplied
through `--certificate-arn` to the stack's proxy Ingress.

Certificate annotations are merged across the shared IngressGroup, allowing
the ALB to use SNI when stacks use different certificates. A wildcard
certificate can instead be passed to every stack. The shared class owns the
HTTPS redirect so stacks cannot configure conflicting redirect behavior. See
the AWS Load Balancer Controller documentation for
[IngressClassParams](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/ingress_class/)
and [IngressGroup annotation behavior](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/annotations/#ingressgroup).

##### Cluster destruction and node drain

Once the cluster-level cleanup checks succeed, `or-eks-cluster` bypasses system
PodDisruptionBudgets during the final node drain so EKS add-ons cannot leave
cluster deletion waiting indefinitely.

#### PosgreSQL data directory

PostgreSQL wants the data folder to be empty (on first startup), but an empty ext4 EBS volume contains a lost+found folder.  
This causes the following error to be reported

```
initdb: error: directory "/var/lib/postgresql/data" exists but is not empty
initdb: detail: It contains a lost+found directory, perhaps due to it being a mount point.
initdb: hint: Using a mount point directly as the data directory is not recommended.
Create a subdirectory under the mount point.
```

For this reason, the volume mount section of the PostgreSQL statefulset has been updated to use subPath

```yaml
volumeMounts:
  - mountPath: /var/lib/postgresql/data
    subPath: psql-data
    name: postgresql-data
```

Make sure the `useSubPath` value is set to true.  
Pods within EKS running linux, `requiresPermissionsFix` must also be set to true to enable the init container (running as root) used to reset ownership and file permissions.

```yaml
initContainers:
  - name: psql-data-ownership
    image: alpine:latest
    command: ["sh", "-c", "chown -R 70:70 /var/lib/postgresql/data && chmod -R 0750 /var/lib/postgresql/data"]
    securityContext:
      runAsUser: 0
    volumeMounts:
      - mountPath: /var/lib/postgresql/data
        subPath: psql-data
        name: postgresql-data
```

### Networking

#### Using HAProxy

In this configuration, we deploy a HAProxy pod within the cluster.  
All traffic gets routed to HAProxy at layer 4 and HAProxy is responsible for TLS termination and management of all further routing to the other pods.  
This is similar to what is done when running under docker compose on a VM.

All OR pods only expose an ClusterIP service and never an Ingress.  
HAProxy exposes a LoadBalancer service for communication from the outside world.  
We're using [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/v2.7/) to automatically create a Network Load Balancer (NLB) based on that service.  
One NLB is automatically created when a (LoadBalancer) Service object exists and destroyed when the Service is deleted.

The namespaced HAProxy profile exposes HTTP and HTTPS by default. `--mqtts`
adds port 8883 to the same per-stack NLB and HAProxy terminates MQTTS with the
same hostname and certificate used for HTTPS before forwarding to Manager's
namespace-local port 1883. `--mqtts-port` can change the public Service port;
the HAProxy container listener remains 8883. Plaintext MQTT is never exposed.

#### Using Ingress with a per-stack gateway

In this configuration, each stack creates one proxy Ingress. The AWS Load
Balancer Controller merges those Ingresses into the shared ALB and targets the
corresponding private HAProxy gateway Pods by IP. Manager and Keycloak expose
only ClusterIP Services and are never direct ALB targets.

The shared `IngressClassParams` supplies the common
[IngressGroup](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/annotations/#ingressgroup)
name, public scheme, IP target type, and HTTPS redirect. Deleting the last
Ingress removes the shared ALB.

The ALB will automatically assign a public "Amazon" DNS name for access.  
We want to use our own DNS names (for ease of use, consistency but also to associate a TLS certificate with it).  
For that, we use [Route 53](https://aws.amazon.com/route53/) and an alias record to directly route traffic to the load balancer, see [Routing traffic to an ELB load balancer - Amazon Route 53](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/routing-to-elb-load-balancer.html).

To create the certificate, we use [AWS Certificate Manager](https://docs.aws.amazon.com/acm/latest/userguide/acm-overview.html).  
Domain ownership validation is performed via DNS record.

The old combined scripts exposed MQTT(S) with additional Manager LoadBalancer
services. The namespaced HAProxy workflow instead keeps Manager private and
reuses the existing proxy NLB when `--mqtts` is selected. Ingress stacks still
use the proxy as their isolation gateway. `or-eks-stack` creates one dedicated
MQTTS NLB per stack, with an existing, cluster-shared, or stack-managed ACM
certificate terminating TLS before plaintext MQTT is forwarded to proxy port
1883 and then Manager. The facade coordinates the endpoint's separate hostname,
certificate lifecycle, readiness, and teardown. Plaintext MQTT is not publicly
exposed.

#### Annotations

AWS LB Controller uses specific annotations on the Ingress and Service to configure the underlying infrastructure (ALB and NLB).  
See the values-eks.yaml files annotations fields for more information.

## Configuration

Some aspects of the setup are configurable.

### eks-common.sh script variables

The script contains several variables that can be changed.

### cluster.yaml

The cluster creation call uses a configuration file.  
This configuration file defines how the cluster is created.  
See [Creating and managing clusters - eksctl](https://eksctl.io/usage/creating-and-managing-clusters/),  
[Config File Schema - eksctl](https://eksctl.io/usage/schema/) and
[eksctl/examples at main · eksctl-io/eksctl](https://github.com/eksctl-io/eksctl/tree/main/examples)
for more information.

### values files

In addition to the default values, values-eks.yaml files are used for the chart deployments.  
These contain EKS specific configuration that can be adapted or complemented if required.

## Running demo under EKS

### Building the OR demo image

Use `./gradlew -PSETUP_JAR=demo clean installDist` to build the controller with the demo setup code.

You can then push the image to the private ECR repository in our developer account with (credentials are for developer account):

```bash
export AWS_ACCESS_KEY_ID="…"
export AWS_SECRET_ACCESS_KEY="…"
export AWS_SESSION_TOKEN="…"

aws ecr get-login-password --region eu-west-1| docker login --username AWS --password-stdin <or-developers account id>.dkr.ecr.eu-west-1.amazonaws.com

docker buildx build --no-cache --push --platform linux/amd64,linux/arm64 -t <or-developers account id>.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager:demo manager/build/install/manager/
```

### Starting a cluster with the OR demo image

Modify the eks-setup-haproxy.sh script to start the manager with the appropriate configuration by replacing lines

```bash
helm install manager manager -f manager/values-haproxy-eks.yaml \
  --set-string or.hostname=$FQDN
```

with

```bash
helm install manager manager -f manager/values-haproxy-eks.yaml -f manager/values-demo.yaml \
  --set-string or.hostname=$FQDN --set-string image.repository=$AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager
```

This values file does two things:

- get the demo image from the private ECR
- enable the demo setup

## Current limitations / explorations to do

Although the eksctl command reports a successful delete of the cluster, the delete operation is still in progress.  
Most of the time, it fails, not being able to delete the VPC.  
It is best to always check the CloudFormation stacks using the AWS Console and manually delete any leftovers.

We are not using EKS Auto Mode for now. When we want to move forward with EKS, we should investigate this option.

The database is a single-pod StatefulSet, and the manager is not currently
architected to support multiple replicas. The manager's use of persistent file
storage should still be reviewed and reduced before production usage.

When moving to production usage, the shell script might be replaced with CloudFormation templates or using Terraform or similar tools.
