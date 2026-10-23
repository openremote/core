#!/bin/bash

. ./eks-common.sh

CLUSTER_VPC_ID=$(aws eks describe-cluster --name $CLUSTER_NAME --query 'cluster.resourcesVpcConfig.vpcId' --output text --profile or)
if [ -z "$CLUSTER_VPC_ID" ] || [ "$CLUSTER_VPC_ID" = "None" ]; then
  echo "Error: Failed to retrieve VPC ID for cluster '$CLUSTER_NAME'. Aborting."
  exit 1
fi

CERTIFICATE_ARN=$(kubectl get ingress manager -o jsonpath='{.metadata.annotations.\alb\.ingress\.kubernetes\.io\/certificate-arn}')
MANAGER_PV_NAME=$(kubectl get pvc manager -o jsonpath='{.spec.volumeName}' 2>/dev/null || true)
PSQL_PV_NAME=$(kubectl get pvc postgresql -o jsonpath='{.spec.volumeName}' 2>/dev/null || true)

DNS_RECORD_NAME=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[0].ResourceRecord.Name")
DNS_RECORD_VALUE=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[0].ResourceRecord.Value")

echo "Delete DNS record $DNS_RECORD_NAME"
aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": '$DNS_RECORD_NAME', "Type": "CNAME", "TTL": 300, "ResourceRecords" : [ { "Value": '$DNS_RECORD_VALUE' } ] } } ]}' \
     --profile dnschg

DNS_RECORD_NAME=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[1].ResourceRecord.Name")
DNS_RECORD_VALUE=$(aws acm describe-certificate --certificate-arn $CERTIFICATE_ARN --profile or --query "Certificate.DomainValidationOptions[1].ResourceRecord.Value")

echo "Delete DNS record $DNS_RECORD_NAME"
aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": '$DNS_RECORD_NAME', "Type": "CNAME", "TTL": 300, "ResourceRecords" : [ { "Value": '$DNS_RECORD_VALUE' } ] } } ]}' \
     --profile dnschg

DNS_NAME=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='application'].DNSName | [0]")
HOSTED_ZONE_ID=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID' && Type=='application'].CanonicalHostedZoneId | [0]")

echo "Delete DNS record $FQDN"

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": "'$FQDN'", "Type": "A", "AliasTarget":{ "HostedZoneId": '$HOSTED_ZONE_ID',"DNSName": '$DNS_NAME',"EvaluateTargetHealth": false} } } ]}' \
     --profile dnschg

DNS_NAME=$(kubectl get svc manager-mqtt -o jsonpath="{.status.loadBalancer.ingress[0].hostname}")
HOSTED_ZONE_ID=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?DNSName=='$DNS_NAME'].CanonicalHostedZoneId | [0]")

echo "Delete DNS record $MQTT_FQDN"

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": "'$MQTT_FQDN'", "Type": "A", "AliasTarget":{ "HostedZoneId": '$HOSTED_ZONE_ID',"DNSName": '\"$DNS_NAME\"',"EvaluateTargetHealth": false} } } ]}' \
     --profile dnschg

DNS_NAME=$(kubectl get svc manager-mqtts -o jsonpath="{.status.loadBalancer.ingress[0].hostname}")
HOSTED_ZONE_ID=$(aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?DNSName=='$DNS_NAME'].CanonicalHostedZoneId | [0]")

echo "Delete DNS record $MQTTS_FQDN"

aws route53 change-resource-record-sets \
    --hosted-zone-id /hostedzone/Z08751721JH0NB6LLCB4V \
    --change-batch \
     '{"Changes": [ { "Action": "DELETE", "ResourceRecordSet": { "Name": "'$MQTTS_FQDN'", "Type": "A", "AliasTarget":{ "HostedZoneId": '$HOSTED_ZONE_ID',"DNSName": '\"$DNS_NAME\"',"EvaluateTargetHealth": false} } } ]}' \
     --profile dnschg

helm uninstall manager
helm uninstall keycloak
helm uninstall postgresql

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

# Give the AWS LB Controller time to delete the ALB and NLB after the Service is deleted
while aws elbv2 describe-load-balancers --profile or --query "LoadBalancers[?VpcId=='$CLUSTER_VPC_ID']" --output text 2>/dev/null | grep -q .; do
  echo "Waiting for load balancer to be deleted..."
  sleep 10
done
helm uninstall aws-load-balancer-controller -n kube-system

echo "Delete certificate $CERTIFICATE_ARN"
MAX_RETRIES=30
for ((i=1; i<=MAX_RETRIES; i++)); do
  if aws acm delete-certificate --certificate-arn "$CERTIFICATE_ARN" --profile or; then
    break
  else
    sleep 10
  fi
done
if (( i > MAX_RETRIES )); then
  echo "⚠️ Timed out trying to delete certificate $CERTIFICATE_ARN after $MAX_RETRIES attempts."
fi

# The component charts retain their PVCs on Helm uninstall. This legacy full
# cleanup explicitly purges them; the StorageClass then deletes their EBS volumes.
kubectl delete pvc manager postgresql --ignore-not-found --wait=true
for pv_name in "$MANAGER_PV_NAME" "$PSQL_PV_NAME"; do
  if [ -n "$pv_name" ] && kubectl get "pv/$pv_name" >/dev/null 2>&1; then
    kubectl wait --for=delete "pv/$pv_name" --timeout=5m
  fi
done
helm uninstall or-setup

# Manually deleting all addons, this should not be required but otherwise the delete cluster fails
# with "2 pods are unevictable from node ..." error messages
eksctl delete addon --cluster $CLUSTER_NAME --name aws-ebs-csi-driver
eksctl delete addon --cluster $CLUSTER_NAME --name vpc-cni
eksctl delete addon --cluster $CLUSTER_NAME --name metrics-server
eksctl delete addon --cluster $CLUSTER_NAME --name coredns
eksctl delete addon --cluster $CLUSTER_NAME --name kube-proxy

eksctl delete cluster --wait --name $CLUSTER_NAME || {
  echo "Clean-up has failed, please check the status in AWS CloudFormation console at https://eu-west-1.console.aws.amazon.com/cloudformation/home?region=eu-west-1#/stacks?filteringText=&filteringStatus=active&viewNested=true"
  echo "Failed clean-ups are often caused by a dangling VPC. Manually delete the VPC and then retry the cluster stack delete operation."
}
