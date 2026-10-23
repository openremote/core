#!/bin/bash

if [ -z "$K_CONTEXT" ]; then
    echo "Error: K_CONTEXT environment variable is not set"
    echo "Please set it to the kubernetes context to use with: export K_CONTEXT=context"
    exit 1
fi

. ./eks-common.sh

# Current charts retain and reuse their PVCs; no static PV rebinding is needed.
helm upgrade --install --kube-context="$K_CONTEXT" --namespace default --wait --timeout 90m postgresql $OR_KUBERNETES_PATH/postgresql -f $OR_KUBERNETES_PATH/postgresql/values-eks.yaml -f "profiles/$OR_PROFILE/postgresql.yaml"

# The Keycloak chart constructs the public issuer from the hostname.
helm upgrade --install --kube-context="$K_CONTEXT" --namespace default --wait --timeout 90m keycloak $OR_KUBERNETES_PATH/keycloak -f $OR_KUBERNETES_PATH/keycloak/values-haproxy.yaml \
  -f "profiles/$OR_PROFILE/keycloak.yaml" --set-string "or.hostname=$FQDN"

# Override the shared Manager template's image repository directly.
helm upgrade --install --kube-context="$K_CONTEXT" --namespace default --wait --timeout 90m manager $OR_KUBERNETES_PATH/manager -f $OR_KUBERNETES_PATH/manager/values-haproxy-eks.yaml \
  -f "profiles/$OR_PROFILE/manager.yaml" --set-string "or.hostname=$FQDN" --set or.setupRunOnRestart=true \
  --set-string image.repository=$AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager
