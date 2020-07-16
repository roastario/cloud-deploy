#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar generate-internal-tunnel-ssl-keystores \
        -p "${TUNNEL_SSL_KEYSTORE_PASSWORD}" \
        -t "${TUNNEL_TRUSTSTORE_PASSWORD}" \
        -e "${TUNNEL_ENTRY_PASSWORD}" \
        -o "${ORGANISATION}" \
        -u "${ORGANISATION_UNIT}" \
        -l "${LOCALITY}" \
        -c "${COUNTRY}"
)
