#!/usr/bin/env bash
# shellcheck disable=SC2086
(
  cd "${WORKING_DIR}" && java -jar /opt/corda/ha-utilities.jar configure-artemis \
    --verbose \
    --logging-level=DEBUG \
    --path="${WORKING_DIR}" \
    --user="${ARTEMIS_X500}" \
    --acceptor-address=${ACCEPTOR_ADDRESS}:${ACCEPTOR_PORT} \
    --keystore="${ARTEMIS_KEYSTORE_PATH}" \
    --truststore="${ARTEMIS_TRUSTSTORE_PATH}" \
    --keystore-password="${ARTEMIS_KEYSTORE_PASSWORD}" \
    --truststore-password="${ARTEMIS_TRUSTSTORE_PASS}" \
    --cluster-password ${ARTEMIS_CLUSTER_PASSWORD}
)
