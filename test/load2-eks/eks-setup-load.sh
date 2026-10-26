#!/usr/bin/env bash

# Retained legacy entry point. See README.md for the separate CLI lifecycles.
printf '%s\n' \
  'This legacy load-test script is no longer supported.' \
  'Use kubernetes/or-eks-cluster for the cluster and kubernetes/or-eks-stack for each stack.' \
  "See the README.md in this script's directory for image, profile, DNS, and cleanup configuration." >&2
exit 1
