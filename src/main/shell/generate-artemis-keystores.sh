#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
mkdir -p "${NODE_STORES_DIR}"
mkdir -p "${BRDIGE_STORES_DIR}"
(
  cd "${WORKING_DIR}" || exit 2
  java -jar /opt/corda/ha-utilities.jar generate-internal-artemis-ssl-keystores --verbose \
    -p "${ARTEMIS_STORE_PASS}" \
    -t "${ARTEMIS_TRUST_PASS}" \
    -o "${ORGANISATION}" \
    -u "${ORGANISATION_UNIT}" \
    -c "${COUNTRY}" \
    -l "${LOCALITY}"
  # shellcheck disable=SC2046
  cp -v $(find . | grep ".jks") "${WORKING_DIR}"

  ## COPY ARTEMIS COMPONENTS TO BRIDGE
  cp -v $(find . | grep "artemis-truststore.jks") "${BRDIGE_STORES_DIR}/artemis-truststore.jks"
  cp -v $(find . | grep "artemisbridge.jks") "${BRDIGE_STORES_DIR}/artemisbridge.jks"

  ## COPY ARTEMIS COMPONENTS TO NODE
  cp -v $(find . | grep "artemis-truststore.jks") "${NODE_STORES_DIR}/artemis-truststore.jks"
  cp -v $(find . | grep "artemisbridge.jks") "${NODE_STORES_DIR}/artemisnode.jks"
)
