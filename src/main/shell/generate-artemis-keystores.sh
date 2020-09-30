#!/usr/bin/env bash
mkdir -p "${WORKING_DIR}"
mkdir -p "${NODE_STORES_DIR}"
mkdir -p "${BRIDGE_STORES_DIR}"
mkdir -p "${ARTEMIS_STORES_DIR}"
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
  cp -v $(find . | grep ".jks") "${WORKING_DIR}" &&

    ## COPY ARTEMIS COMPONENT TO ARTEMIS
    echo "Copying artemis stores to ${BRIDGE_STORES_DIR}" &&
    cp -v "${WORKING_DIR}/artemis-truststore.jks" "${ARTEMIS_STORES_DIR}/artemis-truststore.jks" &&
    cp -v "${WORKING_DIR}/artemis.jks" "${ARTEMIS_STORES_DIR}/artemis.jks" &&
    cp -v "${WORKING_DIR}/artemis-root.jks" "${ARTEMIS_STORES_DIR}/artemis-root.jks" &&

    ## COPY ARTEMIS COMPONENTS TO BRIDGE
    echo "Copying bridge stores to ${BRIDGE_STORES_DIR}" &&
    cp -v "${WORKING_DIR}/artemis-truststore.jks" "${BRIDGE_STORES_DIR}/artemis-truststore.jks" &&
    cp -v "${WORKING_DIR}/artemisbridge.jks" "${BRIDGE_STORES_DIR}/artemisbridge.jks" &&

    ## COPY ARTEMIS COMPONENTS TO NODE
    echo "Copying node stores to ${NODE_STORES_DIR}" &&
    cp -v "${WORKING_DIR}/artemis-truststore.jks" "${NODE_STORES_DIR}/artemis-truststore.jks" &&
    cp -v "${WORKING_DIR}/artemisnode.jks" "${NODE_STORES_DIR}/artemisnode.jks"
)
