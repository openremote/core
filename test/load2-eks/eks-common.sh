#!/bin/bash

set -eo pipefail

# Legacy ACM/NLB experiment only. Use the CLIs for normal deployments.

if [ -z "$AWS_ACCOUNT_ID" ]; then
    echo "Error: AWS_ACCOUNT_ID environment variable is not set"
    echo "Please set it to the ID of the account you want to deploy the cluster into with: export AWS_ACCOUNT_ID=your-account-id"
    exit 1
fi
if [ -z "$AWS_DEVELOPERS_ACCOUNT_ID" ]; then
    echo "Error: $AWS_DEVELOPERS_ACCOUNT_ID environment variable is not set"
    echo "Please set it to the ID of the OpenRemote developers root account with: export AWS_DEVELOPERS_ACCOUNT_ID=dev-account-id"
    exit 1
fi

OR_KUBERNETES_PATH=../../kubernetes
export OR_PROFILE=${OR_PROFILE:-large}

# Name of cluster, not exposed but must be unique within account
export CLUSTER_NAME=${CLUSTER_NAME:-load2-legacy-cluster}

# Hostname to use for public access to this instance, always under the openremote.app domain
FQDN=${FQDN:-load2-legacy.openremote.app}

export AWS_REGION="${AWS_REGION:-eu-west-1}"
K_CONTEXT=${K_CONTEXT:-$CLUSTER_NAME@$AWS_REGION}

printf '%s\n' "Legacy load2 workflow: dedicated cluster $CLUSTER_NAME, default namespace." >&2

DNSCHG_ROLE_ARN="arn:aws:iam::$AWS_DEVELOPERS_ACCOUNT_ID:role/route53-full-access"

aws configure set region $AWS_REGION
aws configure --profile or set region $AWS_REGION
aws configure --profile or set output json
aws configure --profile dnschg set source_profile or
aws configure --profile dnschg set role_arn $DNSCHG_ROLE_ARN

if [ -n "$AWS_ACCESS_KEY_ID" ]; then
  aws configure --profile or set aws_access_key_id $AWS_ACCESS_KEY_ID
fi

if [ -n "$AWS_SECRET_ACCESS_KEY" ]; then
  aws configure --profile or set aws_secret_access_key $AWS_SECRET_ACCESS_KEY
fi

if [ -n "$AWS_SESSION_TOKEN" ]; then
  aws configure --profile or set aws_session_token $AWS_SESSION_TOKEN
fi

aws sts assume-role --role-arn $DNSCHG_ROLE_ARN --role-session-name dnschg --profile or --query "Credentials.[AccessKeyId, SecretAccessKey, SessionToken]" --output text | awk -F'\t' '{print "aws_access_key_id "$1"\naws_secret_access_key "$2"\naws_session_token "$3 }' | xargs -L 1 aws configure --profile dnschg set
