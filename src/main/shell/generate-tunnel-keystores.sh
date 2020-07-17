#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar generate-internal-tunnel-ssl-keystores --verbose \
    -p "${TUNNEL_SSL_KEYSTORE_PASSWORD}" \
    -t "${TUNNEL_TRUSTSTORE_PASSWORD}" \
    -e "${TUNNEL_ENTRY_PASSWORD}" \
    -o "${ORGANISATION}" \
    -u "${ORGANISATION_UNIT}" \
    -c "${COUNTRY}" \
    -l "${LOCALITY}"
)
