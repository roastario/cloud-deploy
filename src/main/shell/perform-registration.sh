#!/usr/bin/env bash
if [ -n "${TRUST_ROOT_DOWNLOAD_URL+x}" ]; then
  echo "Downloading ${TRUST_ROOT_PATH} from URL: ${TRUST_ROOT_DOWNLOAD_URL}"
  wget "${TRUST_ROOT_DOWNLOAD_URL}" -O "${TRUST_ROOT_PATH}"
else
  echo "Expecting to find network-root-truststore at location :${TRUST_ROOT_PATH}"
fi

(cd ${BASE_DIR} &&
  java -jar /opt/corda/ha-utilities.jar node-registration \
    -b ${BASE_DIR} \
    -f ${CONFIG_FILE_PATH} \
    --network-root-truststore ${TRUST_ROOT_PATH} \
    --network-root-truststore-password ${TRUSTSTORE_PASSWORD} \
    --log-to-console)

# shellcheck disable=SC2046
cp -v $(find . | grep ".jks") ${CERTIFICATE_SAVE_FOLDER}
cp -v network-parameters ${NETWORK_PARAMETERS_SAVE_FOLDER}