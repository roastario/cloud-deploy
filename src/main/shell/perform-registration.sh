#!/usr/bin/env bash

(cd ${BASE_DIR} &&
  java -jar /opt/corda/ha-utilities.jar node-registration \
    -b ${BASE_DIR} \
    -f ${CONFIG_FOLDER}/corda.conf \
    --network-root-truststore ${TRUST_ROOT_PATH} \
    --network-root-truststore-password ${TRUSTSTORE_PASSWORD} \
    --log-to-console)
