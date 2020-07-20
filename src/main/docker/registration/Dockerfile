FROM azul/zulu-openjdk-alpine:8u242

RUN apk update && apk upgrade && apk add bash dos2unix

# Create corda user
RUN addgroup corda && \
    adduser corda -G corda -D -s /bin/bash -h /opt/corda

RUN addgroup artemis && \
        adduser artemis -G artemis -D -s /bin/bash -h /opt/artemis

WORKDIR /tmp
RUN wget https://archive.apache.org/dist/activemq/activemq-artemis/2.6.2/apache-artemis-2.6.2-bin.tar.gz
RUN mkdir /opt/artemis-dist && tar -zxvf apache-artemis-2.6.2-bin.tar.gz -C /opt/artemis-dist/ --strip-components=1
RUN mkdir /opt/artemis-data && chown -Rv  artemis /opt/artemis-data
RUN mkdir /opt/artemis-broker && chown -Rv artemis /opt/artemis-broker
RUN mkdir /etc/artemis

COPY ha-utilities.jar /opt/corda/
COPY drivers /opt/corda/drivers
COPY perform-registration.sh /usr/bin/perform-registration
COPY generate-artemis-keystores.sh /usr/bin/generate-artemis-keystores
COPY generate-tunnel-keystores.sh /usr/bin/generate-tunnel-keystores
COPY configure-artemis.sh /usr/bin/configure-artemis
COPY import-node-ssl-to-bridge.sh /usr/bin/import-node-ssl-to-bridge
COPY run-artemis.sh /usr/bin/run-artemis

COPY licenseAgreementCheck.sh /usr/bin/licenseAgreementCheck

RUN dos2unix /usr/bin/* && chmod +x /usr/bin/*

ENV CONFIG_FOLDER="/etc/corda"
ENV BASE_DIR="/opt/corda"
ENV TRUST_ROOT_PATH="/opt/corda/certificates/network-trust-root.jks"


WORKDIR /opt/corda
#USER corda
ENTRYPOINT  ["licenseAgreementCheck"]