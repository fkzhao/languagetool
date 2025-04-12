FROM meddream/jdk17
ARG APP_NAME=flow-gateway

RUN rm -f /etc/localtime \
&& ln -sv /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
&& echo "Asia/Shanghai" > /etc/timezone \
&& apt-get update \
&& apt install tini \
&& mkdir -p /app \
&& mkdir -p /app/models \
&& groupmod --gid 783 --new-name languagetool users \
&& adduser -u 783 -S languagetool -G languagetool -H


WORKDIR /app
COPY languagetool-server/target/*.jar /app/languagetool-server.jar
COPY config/config.properties /app/config.properties
COPY config/logback.xml /app/logback.xml
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV JAVA_HOME=/opt/java/customjre \
    langtool_languageModel=/app/models/ngrams \
    langtool_fasttextBinary=/usr/bin/fasttext \
    langtool_fasttextModel=/app/models/fasttext/lid.176.bin \
    download_ngrams_for_langs=none \
    LOG_LEVEL=INFO \
    LOGBACK_CONFIG=/app/logback.xml \
    DISABLE_PERMISSION_FIX=false \
    DISABLE_FASTTEXT=false


HEALTHCHECK --interval=30s --timeout=10s --start-period=10s CMD wget --quiet --post-data "language=en-US&text=a simple test" -O - http://localhost:8081/v2/check > /dev/null 2>&1  || exit 1

EXPOSE 8081
ENTRYPOINT ["/usr/bin/tini", "-g", "-e", "143", "--", "/app/entrypoint.sh"]