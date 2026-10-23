#!/bin/bash

# Legacy topology: ACM terminates HTTPS and MQTTS at one public NLB.
# Run from this directory, on a dedicated disposable cluster only.
. ./eks-common.sh

envsubst '${CLUSTER_NAME} ${AWS_REGION}' < profiles/$OR_PROFILE/cluster.yaml | eksctl create cluster -f - --profile or
aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" --profile or --alias "$K_CONTEXT"

# Current component charts dynamically provision their own PVCs.
kubectl --context "$K_CONTEXT" apply -f "$OR_KUBERNETES_PATH/cluster/eks/storage-class.yaml"

# [Installation Guide - AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/deploy/installation/)

helm repo add eks https://aws.github.io/eks-charts
helm repo update eks

helm install --kube-context "$K_CONTEXT" aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system --version 1.14.0 \
  --set clusterName=$CLUSTER_NAME \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

# Wait for AWS LB ctrl to be ready
kubectl --context "$K_CONTEXT" rollout status deployment aws-load-balancer-controller -n kube-system --timeout=300s

CLUSTER_DNS=$(kubectl --context "$K_CONTEXT" get svc kube-dns -n kube-system -o jsonpath='{.spec.clusterIP}:{.spec.ports[?(@.name=="dns")].port}')

helm install --kube-context "$K_CONTEXT" --namespace default or-setup "$OR_KUBERNETES_PATH/or-setup"

CERTIFICATE_ARN=$(aws acm request-certificate --domain-name $FQDN --validation-method DNS --profile or --query "CertificateArn" --output text)

DNS_RECORD_NAME=""
DNS_RECORD_VALUE=""
until [ -n "$DNS_RECORD_NAME" ] && [ "$DNS_RECORD_NAME" != "None" ] && [ -n "$DNS_RECORD_VALUE" ] && [ "$DNS_RECORD_VALUE" != "None" ]; do
  echo "Waiting for ACM DNS validation record for $FQDN..."
  DNS_RECORD_NAME=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[0].ResourceRecord.Name" --output text 2>/dev/null)
  DNS_RECORD_VALUE=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[0].ResourceRecord.Value" --output text 2>/dev/null)
  [ -n "$DNS_RECORD_NAME" ] && [ "$DNS_RECORD_NAME" != "None" ] && [ -n "$DNS_RECORD_VALUE" ] && [ "$DNS_RECORD_VALUE" != "None" ] && break
  sleep 5
done

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "UPSERT", "ResourceRecordSet": { "Name": "'$DNS_RECORD_NAME'", "Type": "CNAME", "TTL": 300, "ResourceRecords" : [ { "Value": "'$DNS_RECORD_VALUE'" } ] } } ]}' \
     --profile dnschg

aws acm wait certificate-validated --certificate-arn "$CERTIFICATE_ARN" --profile or

helm install --kube-context "$K_CONTEXT" --namespace default proxy $OR_KUBERNETES_PATH/proxy \
  -f $OR_KUBERNETES_PATH/proxy/values-eks.yaml -f "profiles/$OR_PROFILE/proxy.yaml"  -f values-proxy-acm-load.yaml \
  --set or.nameserver=$CLUSTER_DNS --set or.hostname=$FQDN \
  --set-string 'service.http.annotations.service\.beta\.kubernetes\.io\/aws-load-balancer-ssl-cert'=$CERTIFICATE_ARN \
  --set-string image.repository=$AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/openremote/proxy

helm install --kube-context "$K_CONTEXT" --namespace default postgresql $OR_KUBERNETES_PATH/postgresql -f $OR_KUBERNETES_PATH/postgresql/values-eks.yaml -f "profiles/$OR_PROFILE/postgresql.yaml"

# Waiting for the LB to be created
# AWS LB Controller only creates an Network LB if there's a service

CLUSTER_VPC_ID=$(aws eks describe-cluster --profile or --name $CLUSTER_NAME --query 'cluster.resourcesVpcConfig.vpcId' --output text)
if [ -z "$CLUSTER_VPC_ID" ] || [ "$CLUSTER_VPC_ID" = "None" ]; then
  echo "Error: Failed to retrieve VPC ID for cluster '$CLUSTER_NAME'. Aborting."
  exit 1
fi

while ! aws elbv2 describe-load-balancers  --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network' && Scheme=='internet-facing']" 2>/dev/null | grep '"Code": "active"'; do
  echo "Waiting for load balancer to be created..."
  sleep 10
done

DNS_NAME=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network' && Scheme=='internet-facing'].DNSName | [0]")
HOSTED_ZONE_ID=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network' && Scheme=='internet-facing'].CanonicalHostedZoneId | [0]")

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "UPSERT", "ResourceRecordSet": { "Name": "'$FQDN'", "Type": "A", "AliasTarget":{ "HostedZoneId": '$HOSTED_ZONE_ID',"DNSName": '$DNS_NAME',"EvaluateTargetHealth": false} } } ]}' \
     --profile dnschg

# The Keycloak chart constructs the public issuer from the hostname.
helm install --kube-context "$K_CONTEXT" --namespace default --wait --timeout 90m keycloak $OR_KUBERNETES_PATH/keycloak -f $OR_KUBERNETES_PATH/keycloak/values-haproxy.yaml \
  -f "profiles/$OR_PROFILE/keycloak.yaml" --set-string "or.hostname=$FQDN"
# Override the shared Manager template's image repository directly.
helm install --kube-context "$K_CONTEXT" --namespace default --wait --timeout 90m manager $OR_KUBERNETES_PATH/manager -f $OR_KUBERNETES_PATH/manager/values-haproxy-eks.yaml \
  -f "profiles/$OR_PROFILE/manager.yaml" --set-string "or.hostname=$FQDN" \
  --set-string image.repository=$AWS_DEVELOPERS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/openremote/manager

while ! dig +short $FQDN | grep -qE '^[0-9]'; do
    echo "Waiting for DNS resolution..."
    sleep 5
done

echo "Access the manager at https://$FQDN"

echo "Retrieve the generated admin password (username: admin) with:"
echo "kubectl --context $K_CONTEXT --namespace default get secret openremote-secret -o jsonpath='{.data.admin-password}' | base64 --decode"
