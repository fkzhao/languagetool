FROM meddream/jdk17
ARG APP_NAME=flow-gateway

RUN rm -f /etc/localtime \
&& ln -sv /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
&& echo "Asia/Shanghai" > /etc/timezone \
mkdir -p /app

WORKDIR /app
COPY languagetool-server/target/*.jar /app/languagetool-server.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar","languagetool-server.jar"]