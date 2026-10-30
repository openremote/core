# CI/CD workflow files

The files in this directory are used by the [CI/CD workflows](../.github/workflows) of this repo and custom
projects/forks, which deploy to a Linux host via SSH (see [deployment.yml](../.github/workflows/deployment.yml)).

# `ci_cd.json` File

Configuration file for distribution (pushing openremote/manager docker images) and deploying for this specific
repository; the file is only required if automatic deployments are desired during push/release and/or openremote/manager
docker image tag updates. The file layout is:

```json
{
  // Configure behaviour on push
  "push": {
    // Name of branch to react to (exact match)
    "develop": {
      // Comma separated list of tags to push to openremote/manager docker hub image
      // This can only be used on the OpenRemote Core repo (openremote/core)
      "distribute": {
        "docker": "develop"
      },
      // Singleton or array of deployments to execute, a deployment consists of (environment and/or managerTag see variables for explanation)
      "deploy": {
        "managerTag": "develop", // Can use #ref or exclude this to build COMMIT specific manager docker image (i.e. don't use an image from docker hub) for custom projects will always use openremote version from gradle/libs.versions.toml
        "environment": "staging"
      }
    },
    "main": {
      "distribute": {
        "docker": "latest"
      }
    }
  },
  // Configure behaviour on release
  "release": {
    // Comma separated list of tags to push to openremote/manager docker hub image $version is replaced with release version
    // This can only be used on the OpenRemote Core repo (openremote/core)
    "distribute": {
      "docker": "latest,$version",
      "maven": "$version"
    },
    // Singleton or array of deployments to execute, a deployment consists of (environment and/or managerTag see variables for explanation)
    "deploy": {
      "managerTag": "latest", // Can use #ref or exclude this to build COMMIT specific manager docker image (i.e. don't use an image from docker hub) for custom projects will always use openremote version from gradle/libs.versions.toml
      "environment": "production"
    }
  }
}
```

# `deploy.sh` Bash script

Bash script that handles the actual deployment to a specific host; environment variables should be already loaded into
the shell or be available in `temp.env` file (for environment variables not to be sent to the host) or `temp/env` file (for environment variables that should also be sent to the host) which will be automatically loaded. See [Variables section below](#variables) for available and required values. The `deploy.sh`
in this repository is tailored for deployments on AWS and as such it makes use of the AWS scripts included (to use this functionality then the required environment variables must also be defined), AWS functionality used is as follows:

1. Login to AWS
1. Whitelist deployment runner (machine actioning the deployment) - If `IPV4` or `IPV6` variable is defined
1. Revoke deployment runner from whitelist - Once deployment is completed or failed the deployment runner will be removed from the whitelist

## Docker compose file resolution

The docker compose file used for the deployment is resolved as follows; if resolved file does not exist then deployment
will fail:

- `ENV_COMPOSE_FILE` variable
- `profile/${ENVIRONMENT}.yml` file
- `docker-compose.yml` file

# `env` Directory

Environment variable files that will be loaded when deploying.

The naming convention should be as follows:

- `env/.env` - File containing environment variables that will be loaded for all deployments
- `${ENVIRONMENT}.env` - File containing environment specific environment variables will be loaded if `ENVIRONMENT` variable
  is set for the workflow run; these will be loaded after the `env` file and so can override any value defined there as well
  as being able to add new environment specific values.

# `host_init` Directory

Contains scripts/files required to initialise the host ready for running the stack (e.g. download map tiles etc.); if
the repo is a custom project then any files in the same path of the custom repo will be overlaid on top of the
standard openremote repo files (thus allowing custom project override of any behaviour). The workflow looks for one of
the following bash scripts (in priority order):

- `host_init/init_${ENVIRONMENT}.sh` - Bash script for environment specific host initialisation
- `host_init/init.sh` - Bash script that can be used by any environment as a fallback if no environment specific script
  exists

If an initialisation script is found then the entire `host_init` directory will be copied to the host and the script
will be executed; by copying the entire directory additional files can be included and called from the initialisation
script as required.

# `aws` Directory

Contains AWS scripts and resources for CI/CD within AWS. If the repo is a custom project then any files in the same path
of the custom repo will be overlaid on top of the standard openremote repo files (thus allowing custom project override
of any behaviour).

# Variables

The following variables are supported by deployments; any additional variables can also be used and these are made
available to the shell where the docker compose stack is brought up; so any variables required in the docker compose
profile can be specified in any of the places variables are loaded from (github secrets, inputs, and/or env files).

Standard variables:

- `ENVIRONMENT` - Used to control which env file(s), docker compose file and github secrets to use for deployment.
- `MANAGER_TAG` - The docker tag to pull for the manager image (if not specified)
- `CLEAN_INSTALL` - Indicates if the or_postgresql-data volume should be removed before starting the stack
- `COMMIT` - Which commit or branch to checkout and use on this repo (default: branch/SHA on which workflow is executed)
- `OR_HOSTNAME` - FQDN for the host (default: env/secrets.OR_HOSTNAME)
- `SSH_USER` - SSH username (default: root)
- `SSH_KEY` - SSH private key (this is written to `ssh.key` file and loaded by `deploy.sh` for SSH/SCP commands)
- `SSH_PORT` - SSH port (default: 22)
- `AWS_KEY` - AWS access key ID (should be prefixed with `_TEMP_`
- `AWS_SECRET` - AWS access key secret
- `AWS_REGION` - AWS region to use
- `OR_AWS_ECR_ACCOUNT_ID` - AWS Account ID associated with the ECR repository
- `OR_AWS_ECR_REGION` - AWS Region where the ECR repository is located
- `OR_ADMIN_PASSWORD` - Admin password to be set on deployment; sets the OR_ADMIN_PASSWORD env variable
- `ENV_COMPOSE_FILE` - The full path to the docker compose file to use for the deployment
- `SKIP_HOST_PING` - Will skip host ping check before deploying (default: false)
- `SKIP_SSH_WHITELIST` - Will skip whitelisting the runner in AWS ssh-access security group
- `SKIP_AWS_EC2_START` - Will skip attempt to start EC2 instance named $OR_HOSTNAME when PING fails
- `SKIP_AWS_S3_FILECOPY` - Will skip attempt to copy host deployment files from AWS S3 bucket to `/deployment.local/s3`
- `AWS_EFS_MAP` - Name of EFS map volume to mount at `/deployment.local/map`

## Github secrets

Variables stored as github secrets can use the following prefixes (the prefixes will be automatically stripped); any github secrets that begin with `_` but don't match the following prefixes as well as secrets that contain `.` will be excluded:

- `_TEMP_` - Indicates that the variable should only be used by the deploy script and should not be made available to the host (useful for AWS credentials etc.)
- `_$ENVIRONMENT_` - Variables prefixed with requested `ENVIRONMENT` name (useful for environment specific variable values)
