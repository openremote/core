#!/bin/bash

set -eo pipefail

if [ -z "$K_CONTEXT" ]; then
    echo "Error: K_CONTEXT environment variable is not set"
    echo "Please set it to the kubernetes context to use with: export K_CONTEXT=context"
    exit 1
fi

# Never let this single-stack cleanup remove a namespaced CLI deployment.
CLI_NAMESPACES=$(kubectl --context "$K_CONTEXT" get namespaces -l openremote.io/stack -o name)
if [ -n "$CLI_NAMESPACES" ]; then
  echo "Refusing legacy cleanup: cluster contains CLI-managed stacks." >&2
  exit 1
fi

. ./eks-common.sh

CLUSTER_VPC_ID=$(aws eks describe-cluster --name $CLUSTER_NAME --query 'cluster.resourcesVpcConfig.vpcId' --output text --profile or)
if [ -z "$CLUSTER_VPC_ID" ] || [ "$CLUSTER_VPC_ID" = "None" ]; then
  echo "Error: Failed to retrieve VPC ID for cluster '$CLUSTER_NAME'. Aborting."
  exit 1
fi

DNS_NAME=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network' && Scheme=='internet-facing'].DNSName | [0]")
HOSTED_ZONE_ID=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network' && Scheme=='internet-facing'].CanonicalHostedZoneId | [0]")

echo "Delete DNS record $FQDN"

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": "'$FQDN'", "Type": "A", "AliasTarget":{ "HostedZoneId": '$HOSTED_ZONE_ID',"DNSName": '$DNS_NAME',"EvaluateTargetHealth": false} } } ]}' \
     --profile dnschg

helm uninstall --kube-context="$K_CONTEXT" --namespace default manager
helm uninstall --kube-context="$K_CONTEXT" --namespace default keycloak
helm uninstall --kube-context="$K_CONTEXT" --namespace default postgresql
helm uninstall --kube-context="$K_CONTEXT" --namespace default proxy

echo "Delete VPC peerings"

VPC_PEERING_IDS=$(aws ec2 describe-vpc-peering-connections \
  --filters "Name=accepter-vpc-info.vpc-id,Values=$CLUSTER_VPC_ID" "Name=status-code,Values=active" \
  --query 'VpcPeeringConnections[*].VpcPeeringConnectionId' \
  --profile or \
  --output text)

for peering_id in $VPC_PEERING_IDS; do
  echo "Deleting peering $peering_id"
  aws ec2 delete-vpc-peering-connection --vpc-peering-connection-id $peering_id --profile or
done

# Give the AWS LB Controller time to delete the NLBs after the Service is deleted
while aws elbv2 describe-load-balancers  --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='network']" 2>/dev/null | grep '"Code": "active"'; do
  echo "Waiting for load balancers to be deleted..."
  sleep 10
done
helm uninstall --kube-context="$K_CONTEXT" aws-load-balancer-controller -n kube-system

# Current charts retain namespaced PVCs and the generated Secret on uninstall.
# This cleanup is only for the dedicated legacy cluster, never a CLI cluster.
VOLUME_NAMES=$(kubectl --context "$K_CONTEXT" --namespace default get pvc manager postgresql proxy --ignore-not-found -o jsonpath='{.items[*].spec.volumeName}')
kubectl --context "$K_CONTEXT" --namespace default delete pvc manager postgresql proxy --ignore-not-found
for volume in $VOLUME_NAMES; do
  kubectl --context "$K_CONTEXT" wait --for=delete "pv/$volume" --timeout=10m
done
helm uninstall --kube-context="$K_CONTEXT" --namespace default or-setup
kubectl --context "$K_CONTEXT" --namespace default delete secret openremote-secret --ignore-not-found

# Manually deleting all addons, this should not be required but otherwise the delete cluster fails
# with "2 pods are unevictable from node ..." error messages
eksctl delete addon --profile or --region "$AWS_REGION" --cluster $CLUSTER_NAME --name aws-ebs-csi-driver
eksctl delete addon --profile or --region "$AWS_REGION" --cluster $CLUSTER_NAME --name vpc-cni
eksctl delete addon --profile or --region "$AWS_REGION" --cluster $CLUSTER_NAME --name metrics-server
eksctl delete addon --profile or --region "$AWS_REGION" --cluster $CLUSTER_NAME --name coredns
eksctl delete addon --profile or --region "$AWS_REGION" --cluster $CLUSTER_NAME --name kube-proxy

eksctl delete cluster --profile or --region "$AWS_REGION" --wait --name $CLUSTER_NAME || {
  echo "Clean-up has failed, please check the status in AWS CloudFormation console at https://eu-west-1.console.aws.amazon.com/cloudformation/home?region=eu-west-1#/stacks?filteringText=&filteringStatus=active&viewNested=true"
  echo "Failed clean-ups are often caused by a dangling VPC. Manually delete the VPC and then retry the cluster stack delete operation."
}
