#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar import-ssl-key --verbose \
    --node-keystores="${NODE_KEYSTORE_TO_IMPORT}" \
    --node-keystore-passwords="${NODE_KEYSTORE_PASSWORD}" \
    --base-directory="${WORKING_DIR}" \
    --bridge-keystore="${BRIDGE_KEYSTORE}" \
    --bridge-keystore-password="${BRIDGE_KEYSTORE_PASSWORD}"
  #  cp -v $(find . | grep ".jks") "${WORKING_DIR}"
)
