# Disposable staging lifecycle check

Run this after reviewing the deployment script, before replacing the existing collector.
It uses real Docker/Compose with the cached `grafana/alloy:v1.18.0` image. AWS secret
retrieval alone is replaced with dummy credentials; instance-role secret access was
validated separately. The child process rejects AWS CLI calls and image pulls.

The check creates one random Compose project, a labeled disposable state volume,
private temporary release files and unused loopback listeners. It selects no
application logs, directs telemetry to a reserved non-listening loopback port and
disables Alloy usage reporting in the fixture copy. Existing containers are not
intentionally changed; their IDs, start times and restart counts are compared before
and after. Docker validation helpers are short-lived. The fixture collector is limited
to 512 MiB and half a CPU, but shares host resources with the application.

## 1. Package the reviewed files on your workstation

From the core repository root (include working files, not only committed files):

```sh
tar -czf /tmp/alloy-deployment-check.tar.gz \
  -C .ci_cd/monitoring \
  deploy.py compose.yaml compose.traces.yaml customer.env.example config tests/probe_deploy.py
```

Copy with your usual staging SSH key and address, replacing the placeholders:

```sh
scp -i "<private-key-file>" /tmp/alloy-deployment-check.tar.gz \
  ec2-user@<staging-ssh-address>:alloy-deployment-check.tar.gz
```

## 2. Extract on staging and check the existing collector

```sh
CHECK_DIR=$(mktemp -d "$HOME/alloy-deployment-check.XXXXXX")
tar -xzf "$HOME/alloy-deployment-check.tar.gz" -C "$CHECK_DIR"
cd "$CHECK_DIR"
curl --max-time 5 -fsS http://127.0.0.1:12345/-/ready
printf '\n'
free -h
sudo -n df -hT / /var/lib/docker/volumes
sudo -n findmnt -T /var/lib/docker/volumes -o TARGET,SOURCE,FSTYPE,UUID
```

The existing collector must be ready. Check `available` memory; the runner requires
at least 1 GiB MemAvailable, 1 GiB free on the temporary filesystem and 256 MiB free
on the retained data filesystem. Use the independently recorded data filesystem UUID,
not a newly discovered value without comparison. Avoid concurrent deployments during
the check so that the container comparison is meaningful.

## 3. Run the test

Replace the UUID placeholder with the verified UUID for this host:

```sh
sudo -n python3 -E -s -B tests/probe_deploy.py \
  --data-uuid <verified-data-filesystem-uuid>
printf 'Exit code: %s\n' "$?"
```

Root is needed for Docker, listener ownership inspection and private release files.
No secret ARN, password or AWS credentials need to be supplied. Allow several minutes.
The expected result is nine `PASS` messages and exit code 0:

1. Plan creates no installed configuration or volume.
2. Fresh install is healthy, credentials private, state writable.
3. Unchanged rerun preserves container identity and state.
4. Stopped/missing collector is restored with retained state.
5. Configuration/credential update enables an isolated trace receiver and retains the previous release.
6. Explicit rollback restores old configuration/credentials with the same volume.
7. Invalid Alloy configuration and wrong data UUID leave the running collector unchanged.
8. A fresh configuration directory reuses retained labeled state without initialization.
9. Disposable resources are removed and original container lifecycle is unchanged.

The fresh-directory case simulates loss of boot-disk configuration; it does not replace
an EC2 instance or detach a disk. Trace acceptance verifies the local HTTP receiver,
not Tempo delivery. Central logs/metrics/traces delivery remains a rollout check.

## 4. Confirm the existing collector is still ready

```sh
curl --max-time 5 -fsS http://127.0.0.1:12345/-/ready
printf '\n'
```

Share the PASS/FAIL output, exit code and final readiness result. On failure, share
the printed disposable path and stop before rerunning. Ordinary failures clean up
owned test containers/volume but retain private fixture files, including
`last-command.log` and, when available, `collector.log`. These use dummy credentials.
If ownership checks prevent cleanup, leave those resources for inspection; do not
prune Docker. Successful runs remove their temporary resources and release files;
the uploaded archive and extracted source directory remain for your review.
