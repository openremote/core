#!/bin/bash
# ---------------------------------------------------------------------------------------------------------------------
#                    !!!!!!!!! MUST BE RUN FROM THE REPO ROOT DIR !!!!!!!!!
#
# Script that handles packaging deployment files and executing stack down/up via SCP/SSH; between stack down/up the
# host init script is executed (see .ci_cd/host_init/init.sh) this initialises the host and configures any standard
# deployment maintenance tasks (daily restart, daily backups, etc.).
#
# If AWS CLI authentication variables are configured then this script can use the AWS CLI to perform AWS specific
# configuration; if CIDR env variable is set then this will be temporarily added to the ssh-access security group
# ingress for SSH access on TCP port 22.
#
# Optionally supports rollback using ROLLBACK_ON_ERROR='true' but CLEAN_INSTALL must also be 'true' for rollback to
# work.
# ---------------------------------------------------------------------------------------------------------------------

# Function to be called before exiting to remove runner from AWS ssh-access security group
revoke_ssh () {
  if [ "$SSH_GRANTED" == 'true' ]; then
      if [ -n "$CIDR" ]; then
        "temp/aws/ssh_revoke.sh" "$CIDR" "github-da"
      fi
  fi
}

# Load the environment variables into this session
if [ -f "temp/env" ]; then
  echo "Loading environment variables: 'temp/env'"
  set -a
  . ./temp/env
  set +a
fi

# Load temp environment variables into this session
if [ -f "temp.env" ]; then
  echo "Loading temp environment variables: 'temp.env'"
  set -a
  . ./temp.env
  set +a
fi

# Check host is defined
if [ -z "$OR_HOSTNAME" ]; then
 echo "Host is not set"
 exit 1
fi
HOST="$OR_HOSTNAME"

# Copy CI/CD files into temp dir
echo "Copying CI/CD files into temp dir"
if [ "$IS_CORE_REPO" == 'false' ]; then
  cp -r core/.ci_cd/host_init temp/
  cp -r core/.ci_cd/aws temp/
fi
if [ -d ".ci_cd/host_init" ]; then
  cp -r .ci_cd/host_init temp/
fi
if [ -d ".ci_cd/aws" ]; then
  cp -r .ci_cd/aws temp/
fi

chmod -R +rx temp/

# Determine compose file to use and copy to temp dir (do this here as all env variables are loaded)
if [ -z "$ENV_COMPOSE_FILE" ]; then
  if [ -f "profile/$ENVIRONMENT.yml" ]; then
    cp "profile/$ENVIRONMENT.yml" temp/docker-compose.yml
  elif [ -f "docker-compose.yml" ]; then
    cp docker-compose.yml temp/docker-compose.yml
  fi
elif [ -f "$ENV_COMPOSE_FILE" ]; then
  cp "$ENV_COMPOSE_FILE" temp/docker-compose.yml
else
  cp docker-compose.yml temp/docker-compose.yml
fi
# Check docker compose file is present
if [ ! -f "temp/docker-compose.yml" ]; then
  echo "Couldn't determine docker compose file"
  exit 1
fi

# Set SSH/SCP command variables
sshCommandPrefix="ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
scpCommandPrefix="scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
if [ -n "$SSH_PORT" ]; then
  sshCommandPrefix="$sshCommandPrefix -p $SSH_PORT"
  scpCommandPrefix="$scpCommandPrefix -P $SSH_PORT"
fi
if [ -f "ssh.key" ]; then
  chmod 400 ssh.key
  sshCommandPrefix="$sshCommandPrefix -i ssh.key"
  scpCommandPrefix="$scpCommandPrefix -i ssh.key"
fi
hostStr="$OR_HOSTNAME"
if [ -n "$SSH_USER" ]; then
  hostStr="${SSH_USER}@$hostStr"
fi

# Grant SSH access to this runner's public IP on AWS
if [ "$SKIP_SSH_WHITELIST" != 'true' ] && [ -n "$AWS_ACCESS_KEY_ID" ]; then

  echo "Granting AWS SSH access to github runner"

  source temp/aws/login.sh

  if [ -n "$CIDR" ]; then
    if [ -z "$AWS_ACCOUNT_NAME" ] && [ -z "$AWS_ACCOUNT_ID" ]; then

      echo "Account ID or name is not set so searching for it"
      source temp/aws/get_account_id_from_host.sh

      if [ -z "$AWS_ACCOUNT_ID" ]; then
        echo "Unable to determine account for host '$HOST'"
        exit 1
      fi
    fi

    source temp/aws/set_github-da_account_arn.sh

    echo "Attempting to add runner to AWS SSH whitelist"
    "temp/aws/ssh_whitelist.sh" "$CIDR" "github-runner" "github-da"
    if [ $? -eq 0 ]; then
      SSH_GRANTED=true
    fi
  fi
fi

# Determine host platform via ssh for deployment image building (can't export/import manifests)
PLATFORM=$($sshCommandPrefix $hostStr -- uname -m)
if [ $? -ne 0 ] || [ -z "$PLATFORM" ]; then
  echo "Failed to determine host platform, most likely SSH credentials and/or settings are invalid"
  revoke_ssh
  exit 1
fi
if [ "$PLATFORM" == "x86_64" ]; then
  PLATFORM="amd64"
fi
PLATFORM="linux/$PLATFORM"


# Verify manager tag and create docker image tarballs as required
if [ "$MANAGER_TAG" != '#ref' ]; then
  echo "Checking existence of manager docker image with tag: $MANAGER_TAG"
  docker buildx imagetools inspect --raw openremote/manager:$MANAGER_TAG
  if [ $? -ne 0 ]; then
    echo "Specified manager tag does not exist in docker hub"
    revoke_ssh
    exit 1
  fi
else
  echo "Using commit SHA for manager docker tag: $MANAGER_REF"
  MANAGER_TAG="$MANAGER_REF"
  # Export manager docker image for host platform
  docker build -o type=docker,dest=- --build-arg GIT_REPO=$REPO_NAME --build-arg GIT_COMMIT=$MANAGER_REF --platform $PLATFORM -t openremote/manager:$MANAGER_REF $MANAGER_DOCKER_BUILD_PATH | gzip > temp/manager.tar.gz
  if [ $? -ne 0 ] || [ ! -f temp/manager.tar.gz ]; then
    echo "Failed to export manager image with tag: $MANAGER_REF"
    revoke_ssh
    exit 1
  fi
fi
if [ -n "$DEPLOYMENT_REF" ]; then
  # Export deployment docker image for host platform
  docker build -o type=docker,dest=- --build-arg GIT_REPO=$REPO_NAME --build-arg GIT_COMMIT=$DEPLOYMENT_REF --platform $PLATFORM -t openremote/deployment:$DEPLOYMENT_REF $DEPLOYMENT_DOCKER_BUILD_PATH | gzip > temp/deployment.tar.gz
  if [ $? -ne 0 ] || [ ! -f temp/deployment.tar.gz ]; then
    echo "Failed to export deployment image"
    revoke_ssh
    exit 1
  fi
fi

# Set version variables
MANAGER_VERSION="$MANAGER_TAG"
DEPLOYMENT_VERSION="$DEPLOYMENT_REF"
echo "MANAGER_VERSION=\"$MANAGER_VERSION\"" >> temp/env
echo "DEPLOYMENT_VERSION=\"$DEPLOYMENT_VERSION\"" >> temp/env

echo "GZipping temp dir"
tar -zcvf temp.tar.gz temp

echo "Copying temp dir to host"
$scpCommandPrefix temp.tar.gz ${hostStr}:~

if [ "$ROLLBACK_ON_ERROR" == 'true' ]; then
  if [ "$CLEAN_INSTALL" != 'true' ]; then
    echo "ROLLBACK_ON_ERROR can only be used if CLEAN_INSTALL is set"
    ROLLBACK_ON_ERROR=false
  fi
fi

echo "Running deployment on host"
$sshCommandPrefix ${hostStr} << EOF

if [ "$ROLLBACK_ON_ERROR" == 'true' ]; then
  echo "Moving old temp dir to temp_old"
  rm -fr temp_old
  mv temp temp_old
  # Tag existing manager image with previous tag (current tag might not be available in docker hub anymore or it could have been overwritten)
  docker tag '`docker images openremote/manager -q | head -1`' openremote/manager:previous
else
  echo "Removing old temp deployment dir"
  rm -fr temp
fi

echo "Extracting temp dir"
tar -xvzf temp.tar.gz
chmod +x -R temp/

set -a
. ./temp/env
set +a

if [ -f "temp/manager.tar.gz" ]; then
  echo "Loading manager docker image"
  docker load < temp/manager.tar.gz
fi

if [ -f "temp/deployment.tar.gz" ]; then
  echo "Loading deployment docker image"
  docker load < temp/deployment.tar.gz
fi

# Calculate DB memory settings
. ./temp/host_init/calculate_db_memory.sh

# Calculate host RAM
export OR_RAM="$(( $(free -m | awk '/^Mem:/{print $2}') * 40 / 100 ))"
export OR_KC_RAM="$(( $(free -m | awk '/^Mem:/{print $2}') * 20 / 100 ))"
export OR_DB_RAM="$(( $(free -m | awk '/^Mem:/{print $2}') * 25 / 100 ))"

# Login to Private ECR Registry to download restricted images
if [ -n "$OR_AWS_ECR_ACCOUNT_ID" ] && [ -n "$OR_AWS_ECR_REGION" ]; then
  aws ecr get-login-password --region $OR_AWS_ECR_REGION | docker login --username AWS --password-stdin $OR_AWS_ECR_ACCOUNT_ID.dkr.ecr.$OR_AWS_ECR_REGION.amazonaws.com &>/dev/null
fi

# Make sure we have correct keycloak, proxy and postgres images
echo "Pulling requested service versions from docker hub"
docker-compose -p or -f temp/docker-compose.yml pull --ignore-pull-failures

if [ \$? -ne 0 ]; then
  echo "Deployment failed to pull docker images"
  exit 1
fi

# Attempt docker compose down
CONTAINER_IDS=\$(docker ps -q)
if [ -n "\$CONTAINER_IDS" ]; then
  echo "Stopping existing stack"
  docker-compose -f temp/docker-compose.yml -p or down --remove-orphans 2> /dev/null

  if [ \$? -ne 0 ]; then
    echo "Deployment failed to stop the existing stack"
    exit 1
  fi
fi

# Run host init
hostInitCmd=
if [ -n "$HOST_INIT_SCRIPT" ]; then
  if [ ! -f "temp/host_init/${HOST_INIT_SCRIPT}.sh" ]; then
    echo "HOST_INIT_SCRIPT (temp/host_init/${HOST_INIT_SCRIPT}.sh) does not exist"
    exit 1
  fi
  hostInitCmd="temp/host_init/${HOST_INIT_SCRIPT}.sh"
elif [ -f "temp/host_init/init_${ENVIRONMENT}.sh" ]; then
  hostInitCmd="temp/host_init/init_${ENVIRONMENT}.sh"
elif [ -f "temp/host_init/init.sh" ]; then
  hostInitCmd="temp/host_init/init.sh"
fi
if [ -n "\$hostInitCmd" ]; then
  echo "Running host init script: '\$hostInitCmd'"
  sudo -E \$hostInitCmd
else
  echo "No host init script"
fi

# Delete any deployment volume so we get the latest
echo "Deleting existing deployment data volume"
docker volume rm or_deployment-data 1>/dev/null

# Get IP of interface on private subnet to expose metrics
TOKEN=\$(curl -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null)

if [ -n "\$TOKEN" ]; then
  export PRIVATE_IP=\$(curl -H "X-aws-ec2-metadata-token: \$TOKEN" -s http://169.254.169.254/latest/meta-data/local-ipv4 2>/dev/null)
else
  echo "Could not get token to query for private IP"
fi
echo "Private IP \$PRIVATE_IP"

# Start the stack, including any one-shot deployment service, and wait for readiness
if ! bash temp/host_init/start.sh temp/docker-compose.yml or; then
  exit 1
fi

# Run host post init
hostPostInitCmd=
if [ -f "temp/host_init/post_init_${ENVIRONMENT}.sh" ]; then
  hostPostInitCmd="temp/host_init/post_init_${ENVIRONMENT}.sh"
elif [ -f "temp/host_init/post_init.sh" ]; then
  hostPostInitCmd="temp/host_init/post_init.sh"
fi
if [ -n "\$hostPostInitCmd" ]; then
  echo "Running host post init script: '\$hostPostInitCmd'"
  sudo -E \$hostPostInitCmd
else
  echo "No host post init script"
fi

# Store deployment snapshot data if the host can access S3 bucket with the same name as the host
docker image inspect \$(docker image ls -aq) > temp/image-info.txt
docker inspect \$(docker ps -aq) > temp/container-info.txt

aws s3 cp temp/image-info.txt s3://${OR_HOSTNAME}/image-info.txt &>/dev/null
aws s3 cp temp/container-info.txt s3://${OR_HOSTNAME}/container-info.txt &>/dev/null
exit 0
EOF

if [ $? -ne 0 ]; then
  echo "Deployment failed or is unhealthy"
  revoke_ssh
  exit 1
fi

echo "Testing manager web server https://$OR_HOSTNAME..."
response=$(curl --output /dev/null --silent --head --write-out "%{http_code}" https://$OR_HOSTNAME/manager/)
count=0
while [[ $response -ne 200 ]] && [ $count -lt 12 ]; do
  echo "https://$OR_HOSTNAME/manager/ RESPONSE CODE: $response...Sleeping 5 seconds"
  sleep 5
  response=$(curl --output /dev/null --silent --head --write-out "%{http_code}" https://$OR_HOSTNAME/manager/)
  count=$((count+1))
done

if [ $response -ne 200 ]; then
  echo "Manager web server is unreachable https://$OR_HOSTNAME..."
  revoke_ssh
  exit 1
else
  echo "Manager web server now reachable https://$OR_HOSTNAME..."
fi

revoke_ssh
exit 0

# ROLLBACK FUNCTIONALITY DISABLED FOR NOW AS WASN'T WORKING AND MANUAL INTERVENTION IS FINE AT THIS POINT

#if [ "$ROLLBACK_ON_ERROR" != 'true' ]; then
#else
#  DO_ROLLBACK=true
#fi

if [ "$DO_ROLLBACK" == 'true' ]; then
  echo "Attempting rollback"
  $sshCommandPrefix ${hostStr} << EOF

if [ ! -d "temp_old" ]; then
  echo "Previous deployment files not found so cannot rollback"
  exit 1
fi

rm -fr temp
mv temp_old temp

# Set MANAGER_VERSION to previous
echo 'MANAGER_VERSION="previous"' >> temp/env

set -a
. ./temp/env
set +a

if [ -f "temp/deployment.tar.gz" ]; then
  echo "Loading deployment docker image"
  docker load < temp/deployment.tar.gz
fi

# Login to Private ECR Registry to download restricted images
if [ -n "$OR_AWS_ECR_ACCOUNT_ID" ] && [ -n "$OR_AWS_ECR_REGION" ]; then
  aws ecr get-login-password --region $OR_AWS_ECR_REGION | docker login --username AWS --password-stdin $OR_AWS_ECR_ACCOUNT_ID.dkr.ecr.$OR_AWS_ECR_REGION.amazonaws.com &>/dev/null
fi

# Make sure we have correct keycloak, proxy and postgres images
echo "Pulling requested service versions from docker hub"
docker-compose -p or -f temp/docker-compose.yml pull --ignore-pull-failures

if [ \$? -ne 0 ]; then
  echo "Deployment failed to pull docker images"
  exit 1
fi

# Attempt docker compose down
echo "Stopping existing stack"
docker-compose -f temp/docker-compose.yml -p or down 2> /dev/null

# Run host init
hostInitCmd=
if [ -n "$HOST_INIT_SCRIPT" ]; then
  if [ ! -f "temp/host_init/${HOST_INIT_SCRIPT}.sh" ]; then
    echo "HOST_INIT_SCRIPT (temp/host_init/${HOST_INIT_SCRIPT}.sh) does not exist"
    exit 1
  fi
  hostInitCmd="temp/host_init/${HOST_INIT_SCRIPT}.sh"
elif [ -f "temp/host_init/init_${ENVIRONMENT}.sh" ]; then
  hostInitCmd="temp/host_init/init_${ENVIRONMENT}.sh"
elif [ -f "temp/host_init/init.sh" ]; then
  hostInitCmd="temp/host_init/init.sh"
fi
if [ -n "\$hostInitCmd" ]; then
  echo "Running host init script: '\$hostInitCmd'"
  sudo -E \$hostInitCmd
else
  echo "No host init script"
fi

# Delete any deployment volume so we get the latest
echo "Deleting existing deployment data volume"
docker volume rm or_deployment-data 1>/dev/null

# Start the stack, including any one-shot deployment service, and wait for readiness
if ! bash temp/host_init/start.sh temp/docker-compose.yml or; then
  exit 1
fi

# Run host post init
hostPostInitCmd=
if [ -f "temp/host_init/post_init_${ENVIRONMENT}.sh" ]; then
  hostPostInitCmd="temp/host_init/post_init_${ENVIRONMENT}.sh"
elif [ -f "temp/host_init/post_init.sh" ]; then
  hostPostInitCmd="temp/host_init/post_init.sh"
fi
if [ -n "\$hostPostInitCmd" ]; then
  echo "Running host post init script: '\$hostPostInitCmd'"
  sudo -E \$hostPostInitCmd
else
  echo "No host post init script"
fi

EOF
fi

echo "Testing manager web server https://$OR_HOSTNAME..."
response=$(curl --output /dev/null --silent --head --write-out "%{http_code}" https://$OR_HOSTNAME/manager/)
count=0
while [[ $response -ne 200 ]] && [ $count -lt 12 ]; do
  echo "https://$OR_HOSTNAME/manager/ RESPONSE CODE: $response...Sleeping 5 seconds"
  sleep 5
  response=$(curl --output /dev/null --silent --head --write-out "%{http_code}" https://$OR_HOSTNAME/manager/)
  count=$((count+1))
done

if [ $response -ne 200 ]; then
  echo "Manager web server is unreachable https://$OR_HOSTNAME..."
  revoke_ssh
  exit 1
else
  echo "Manager web server now reachable https://$OR_HOSTNAME..."
fi

revoke_ssh
