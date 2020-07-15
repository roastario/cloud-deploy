#!/usr/bin/env bash

#ARTEMIS_STORE_PASS="$(cat "${ARTEMIS_STORE_PASS_SECRET_FILE}")"
#ARTEMIS_TRUST_PASS="$(cat "${ARTEMIS_TRUST_PASS_SECRET_FILE}")"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar generate-internal-artemis-ssl-keystores -p "${ARTEMIS_STORE_PASS}" -t "${ARTEMIS_TRUST_PASS}"
)
