FROM azul/zulu-openjdk-alpine:8u242

RUN apk update && apk upgrade && apk add bash

# Create corda user
RUN addgroup corda && \
    adduser corda -G corda -D -s bash -h /opt/corda

COPY ha-utilities.jar /opt/corda/
COPY drivers /opt/corda/drivers
COPY perform-registration.sh /usr/bin/perform-registration
COPY licenseAgreementCheck.sh /usr/bin/licenseAgreementCheck
ENV CONFIG_FOLDER="/etc/corda"
ENV BASE_DIR="/opt/corda"
ENV TRUST_ROOT_PATH="/opt/corda/certificates/network-trust-root.jks"

WORKDIR /opt/corda
ENTRYPOINT  ["licenseAgreementCheck"]