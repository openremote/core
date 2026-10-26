#!/bin/bash

. ./eks-common.sh

envsubst < cluster.yaml | eksctl create cluster -f - --profile or

# [Installation Guide - AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/deploy/installation/)

helm repo add eks https://aws.github.io/eks-charts
helm repo update eks

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=$CLUSTER_NAME \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

# Wait for AWS LB ctrl to be ready
kubectl rollout status deployment aws-load-balancer-controller -n kube-system --timeout=300s

CLUSTER_DNS=$(kubectl get svc kube-dns -n kube-system -o jsonpath='{.spec.clusterIP}:{.spec.ports[?(@.name=="dns")].port}')

kubectl apply -f cluster/eks/storage-class.yaml

helm install or-setup or-setup

helm install proxy proxy -f proxy/values-eks.yaml --set or.nameserver=$CLUSTER_DNS --set or.hostname=$FQDN

helm install postgresql postgresql -f postgresql/values-eks.yaml

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

helm install keycloak keycloak -f keycloak/values-haproxy.yaml \
  --set-string or.hostname=$FQDN
helm install manager manager -f manager/values-haproxy-eks.yaml \
  --set-string or.hostname=$FQDN

while ! dig +short $FQDN | grep -qE '^[0-9]'; do
    echo "Waiting for DNS resolution..."
    sleep 5
done

# Now that DNS is in place, HAProxy can properly create the certificate
kubectl exec $(kubectl get pod -l "app.kubernetes.io/name=proxy" -o name) -- sh -c "/entrypoint.sh add $FQDN" || {
    echo "Certificate generation failed, please wait a moment and manually execute the following command:"
    echo "kubectl exec \$(kubectl get pod -l \"app.kubernetes.io/name=proxy\" -o name) -- sh -c \"/entrypoint.sh add $FQDN\""
}

echo "Access the manager at https://$FQDN"
