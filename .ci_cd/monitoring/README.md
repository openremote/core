# Standalone Alloy with Docker Compose

One Linux collector forwards Docker logs to Loki and embedded host metrics to
Prometheus. An optional override receives application traces and forwards them to
Tempo. The collector is independent of the application Compose network and lifecycle.
Application logging to CloudWatch and existing application-metric scrapes continue.

This is the collector configuration. Automated deployment through SSM, Secrets
Manager retrieval, data-mount checks and release/rollback handling are still being
implemented. Do not use this change alone to replace a running collector.

## Files and settings

- `compose.yaml`: logs and host metrics, one service and external state volume.
- `compose.traces.yaml`: optional traces in the same service.
- `config/`: one source for logs, host metrics and trace pipelines.
- `customer.env.example`: non-secret settings and password file paths.

Keep one environment file in the customer repository. Set independent, stable
`MONITORING_COMPOSE_PROJECT` and `ALLOY_DATA_VOLUME` names; neither should contain
an instance ID or deployment attempt. Select the application project and services
explicitly. Both selection values are regular expressions; escape metacharacters
in project names if needed. The example selects manager, proxy, PostgreSQL and
Keycloak. Extend the service expression when other application logs are needed.

Coordinate `HOST_METRICS_JOB` and `HOST_METRICS_INSTANCE` with the existing central
node-exporter scrape before cutover. Use distinct temporary jobs for comparison;
never have two writers for the same canonical host series. Manager, proxy and
query-exporter central scrapes remain unchanged.

The deployment layer will retrieve Secrets Manager passwords into private,
root-owned files. Compose mounts these files; it does not fetch them. Loki and
Tempo share the Loki username/password, and the username determines the Tempo
tenant at the gateway. Prometheus ingestion uses a separate credential. Never put
passwords in the environment file or deployment artifacts.

Render the base configuration from the core repository:

```sh
docker compose --env-file /path/to/customer/deployment/alloy.env \
  -f .ci_cd/monitoring/compose.yaml config --quiet
```

## Optional traces

Without `compose.traces.yaml`, there is no trace pipeline, OTLP listener or Tempo
setting requirement. To enable traces, set these values in the environment file,
using the actual private IP assigned to the Docker host:

```dotenv
OTLP_GRPC_LISTEN_ADDRESS=10.0.1.10:4317
OTLP_HTTP_LISTEN_ADDRESS=10.0.1.10:4318
TEMPO_OTLP_ENDPOINT=https://tempo.example.com
```

Render both files together:

```sh
docker compose --env-file /path/to/customer/deployment/alloy.env \
  -f .ci_cd/monitoring/compose.yaml \
  -f .ci_cd/monitoring/compose.traces.yaml config --quiet
```

Use the same file selection for deployment, inspection and rollback. The override
adds a single configuration mount and trace environment settings to the existing
service; it does not create a second collector or join an application network.

An instrumented application container on that host can use:

```dotenv
OTEL_EXPORTER_OTLP_ENDPOINT=http://10.0.1.10:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_SERVICE_NAME=manager
```

The SDK adds `/v1/traces` to this base endpoint. For gRPC, use
`http://10.0.1.10:4317` with protocol `grpc`. Applications still need their normal
OpenTelemetry instrumentation enabled. Configure the endpoint through environment
settings so it can be updated when an instance's private IP changes.

The old `alloy-otel` Docker alias is no longer provided. Loopback listeners work for
host-local clients, but `127.0.0.1` inside a bridge-network application container is
that container, not the host. Choose an explicit private host address reachable from
application containers; do not bind to `0.0.0.0`, `[::]` or a public address.

These receivers use plaintext OTLP without client authentication. Restrict access
to intended senders through the host firewall and applicable AWS security groups;
a private IP alone is not an access boundary. No public ingress rule is required.
The deployment layer must validate address ownership and port conflicts before
startup. Compose requires values but does not enforce their private-address status.
With host networking there are no Docker port mappings: absence of `ports` does
not mean OTLP is inaccessible. Check actual listeners and application reachability
before enabling traces on a real host.

The trace pipeline preserves the existing resource attributes
`deployment.environment.name`, `openremote.customer` and `cloud.account.id`,
overriding incoming values for those keys. Application `service.name` is preserved.
Batching and the memory limiter remain enabled; the latter applies backpressure
under pressure but does not guarantee that the process cannot exhaust memory.
Review combined logs/metrics/trace load during runtime acceptance.

## Runtime and persistence

The target is Linux, including ARM64 Amazon Linux 2023. Host network/PID/UTS
namespaces and read-only root/proc/sys mounts allow the embedded exporter to report
the host. The Alloy management interface listens only on
`127.0.0.1:${ALLOY_HTTP_PORT}`. There is no standalone node-exporter port.

The container drops all capabilities and uses a read-only filesystem, a 512 MiB
memory limit, 0.5 CPU and `GOMEMLIMIT=384MiB`. Supplementary group 473 permits access
to the pinned image's state directory. SELinux labeling is disabled for the collector
to read host mounts without relabeling them. Docker socket access is privileged even
with a read-only mount; keep the image and configuration trusted.

The external named volume must already exist and be writable. Deployment must verify
that the expected data filesystem is mounted at `/var/lib/docker/volumes` before
creating state or starting Alloy. Create state only for a fresh installation;
reuse its stable volume after boot-volume replacement. Rebuild configuration and
password files on the new boot disk. Do not use `down --volumes` or global pruning.

Host metrics include `/` and `/var/lib/docker/volumes`, exclude transient container
filesystems/interfaces and read udev metadata through the host-root mount. Metrics
have a bounded-age WAL on the state volume, not a disk quota or delivery guarantee.
Logs retain read positions but pending Loki batches are not durably buffered. The
trace pipeline has no persistent sending queue. Gaps across restarts remain possible.

## Validate and inspect

With Docker, Python 3 and the pinned image available, run from the repository root:

```sh
docker pull grafana/alloy:v1.18.0
python3 .ci_cd/monitoring/tests/validate.py
```

This renders the base and trace-enabled Compose models and validates their mounted
Alloy files with the rendered environment. Disposable validation containers have
no network, Docker socket, host mounts or persistent state; they use dummy passwords
and start no collector. No AWS or backend connection is made. Compose plugin and
standalone executable forms are supported.

These checks establish syntax, component references and correct optional composition.
They do not establish host permissions or delivery. Later isolated runtime acceptance
must cover host mounts/state access and, when enabled, trace reception and forwarding.
Before live replacement, review the existing collector and tracing clients together.

After an approved deployment, use the selected Compose files with `ps` and
`logs --tail=100 alloy`, and check the loopback `/-/ready` endpoint. Inspect component
health in the Alloy UI. Readiness alone does not establish central delivery: verify
fresh logs, host metrics and (when enabled) traces in Grafana.

Example Loki query:

```logql
{customer="customer", environment="staging", service="manager"}
  | container="or-manager-1"
```

Customer, environment, service, compose project and AWS account are indexed log
labels. Container and host are structured metadata. Manager exceptions are grouped
as multiline events, and manager/PostgreSQL severities are normalized without
adding indexed level labels. Those log processing rules are unchanged.

## Updating the previous Compose setup

This configuration supersedes the former logs/traces-only stack. It is not a drop-in
restart: supply the new metrics settings and explicitly select the retained volume
name (including an existing project prefix), review host mounts and memory limits,
and change application trace endpoints when enabling the override. Keep the previous
manual files for the agreed fallback; automatic adoption is not provided.

The deployment tooling will retain the selected file set and resolved image override
per release. Removing the trace override on a later deploy intentionally stops trace
reception; coordinate that with application configuration.

References: [Compose file merging](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/),
[host networking](https://docs.docker.com/engine/network/drivers/host/).
