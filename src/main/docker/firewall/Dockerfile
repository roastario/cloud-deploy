FROM azul/zulu-openjdk-alpine:8u242

RUN apk update && apk upgrade && apk add bash

# Create corda user
RUN addgroup corda && \
    adduser corda -G corda -D -s bash -h /opt/corda

