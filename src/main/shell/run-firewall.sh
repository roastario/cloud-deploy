#!/usr/bin/env bash

set -u
set -x

java -Djava.security.egd=file:/dev/./urandom \
  -Dcapsule.jvm.args="${JVM_ARGS}" \
  -jar /opt/corda/firewall.jar \
  --base-directory ${BASE_DIR} \
  --config-file=${CONFIG_FILE} \
  --verbose \
  --logging-level=DEBUG
