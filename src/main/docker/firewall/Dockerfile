FROM azul/zulu-openjdk-alpine:8u242

RUN apk update && apk upgrade && apk add bash

# Create corda user
RUN addgroup corda && \
    adduser corda -G corda -D -s bash -h /opt/corda

COPY firewall.jar /opt/corda/
COPY run-firewall.sh /usr/bin/run-firewall
COPY licenseAgreementCheck.sh /usr/bin/licenseAgreementCheck
ENV CONFIG_FOLDER="/etc/corda"
ENV BASE_DIR="/opt/corda"

USER "corda"

ENTRYPOINT  ["licenseAgreementCheck"]
CMD ["run-firewall"]