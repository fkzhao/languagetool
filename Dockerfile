ARG LT_VERSION=6.5
ARG JAVA_VERSION=jdk-17.0.9+9
FROM alpine:3.21.3 AS base

ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

RUN set -eux; \
    apk add --no-cache libretls musl-locales musl-locales-lang tzdata zlib unzip; \
    rm -rf /var/cache/apk/*

ENV JAVA_HOME=/opt/jvm \
    JAVA_VERSION=${JAVA_VERSION}

RUN set -eux; \
    apk add --no-cache binutils; \
    rm -rf /var/cache/apk/*
# hadolint ignore=SC3060
# hadolint ignore=DL4006
RUN set -eux; \
    RELEASE_PATH="${JAVA_VERSION/+/%2B}"; \
    RELEASE_TYPE="${JAVA_VERSION%-*}"; \
    RELEASE_NUMBER="${JAVA_VERSION#*-}"; \
    RELEASE_NUMBER="${RELEASE_NUMBER/+/_}"; \
    URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.9%2B9/OpenJDK17U-jre_x64_alpine-linux_hotspot_17.0.9_9.tar.gz"; \
    CHKSUM=$(wget --quiet -O -  "${URL}.sha256.txt" | cut -d' ' -f1); \
    wget -O /tmp/jre.tar.gz ${URL}; \
    echo "${CHKSUM} */tmp/jre.tar.gz" | sha256sum -c -; \
    mkdir -p "${JAVA_HOME}"; \
    mkdir -p /languagetool; \
    mkdir -p /models; \
    mkdir -p /models/fasttext; \
    mkdir -p /models/ngrams; \
    tar --extract \
        --file /tmp/jre.tar.gz \
        --directory "${JAVA_HOME}" \
        --strip-components 1 \
        --no-same-owner \
    ; \
    rm /tmp/jre.tar.gz;

COPY languagetool-server/target/languagetool-server.jar /languagetool/languagetool-server.jar
COPY config/logback.xml /languagetool/logback.xml
COPY config/config.properties /languagetool/config.properties

RUN set -eux; \
    apk add --no-cache bash shadow libstdc++ gcompat su-exec tini xmlstarlet fasttext; \
    rm -f /var/cache/apk/*; \
    groupmod --gid 783 --new-name languagetool users; \
    adduser -u 783 -S languagetool -G languagetool -H


ENV langtool_languageModel=/models/ngrams \
    langtool_fasttextBinary=/usr/bin/fasttext \
    langtool_fasttextModel=/models/fasttext/lid.176.bin \
    download_ngrams_for_langs=none \
    MAP_UID=783 \
    MAP_GID=783 \
    LOG_LEVEL=INFO \
    LOGBACK_CONFIG=/languagetool/logback.xml \
    DISABLE_PERMISSION_FIX=false \
    DISABLE_FASTTEXT=false

ENV PATH=${JAVA_HOME}/bin:${PATH}

WORKDIR /languagetool

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s CMD wget --quiet --post-data "language=en-US&text=a simple test" -O - http://localhost:8010/v2/check > /dev/null 2>&1  || exit 1
EXPOSE 8010

ENTRYPOINT ["/sbin/tini", "-g", "-e", "143", "--", "/entrypoint.sh"]

