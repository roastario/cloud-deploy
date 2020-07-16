#!/usr/bin/env bash
: ${JVM_ARGS='-XX:+UseG1GC'}

java -Djava.security.egd=file:/dev/./urandom -Dcapsule.jvm.args="${JVM_ARGS}" -jar /opt/corda/firewall.jar --base-directory ${BASE_DIR} --config-file=${CONFIG_FOLDER}/firewall.conf ${FIREWALL_ARGS}
