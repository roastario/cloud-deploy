#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar generate-internal-artemis-ssl-keystores --verbose \
    -p "${ARTEMIS_STORE_PASS}" \
    -t "${ARTEMIS_TRUST_PASS}" \
    -o "${ORGANISATION}" \
    -u "${ORGANISATION_UNIT}" \
    -c "${COUNTRY}" \
    -l "${LOCALITY}"
)
