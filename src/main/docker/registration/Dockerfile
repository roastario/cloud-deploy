FROM azul/zulu-openjdk-alpine:8u242

RUN apk update && apk upgrade && apk add bash

# Create corda user
RUN addgroup corda && \
    adduser corda -G corda -D -s bash -h /opt/corda

RUN mkdir /opt/artemis
WORKDIR /tmp
RUN wget https://archive.apache.org/dist/activemq/activemq-artemis/2.8.1/apache-artemis-2.8.1-bin.tar.gz
RUN tar -zxvf apache-artemis-2.8.1-bin.tar.gz -C /opt/artemis/ --strip-components=1


COPY ha-utilities.jar /opt/corda/
COPY drivers /opt/corda/drivers
COPY perform-registration.sh /usr/bin/perform-registration
COPY generate-artemis-keystores.sh /usr/bin/generate-artemis-keystores
COPY licenseAgreementCheck.sh /usr/bin/licenseAgreementCheck

RUN chmod +x /usr/bin/perform-registration /usr/bin/generate-artemis-keystores
ENV CONFIG_FOLDER="/etc/corda"
ENV BASE_DIR="/opt/corda"
ENV TRUST_ROOT_PATH="/opt/corda/certificates/network-trust-root.jks"



WORKDIR /opt/corda
USER corda
ENTRYPOINT  ["licenseAgreementCheck"]