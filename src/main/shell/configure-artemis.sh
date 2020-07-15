# shellcheck disable=SC2086

java -jar /opt/corda-tools-ha-utilities.jar \
configure-artemis \
--verbose \
--logging-level=DEBUG \
--install \
--path='/opt/artemis' \
--user="${ARTEMIS_X500}" \
--acceptor-address=${ACCEPTOR_ADDRESS}:${ACCEPTOR_PORT} \
--keystore=${ARTEMIS_KEYSTORE_PATH} \
--keystore-password=${ARTEMIS_KEYSTORE_PASSWORD} \
--truststore=${ARTEMIS_TRUSTSTORE_PATH} \
--truststore-password=${ARTEMIS_TRUSTSTORE_PASS}
