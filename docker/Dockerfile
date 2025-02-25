ARG baseImage=ghcr.io/wavesplatform/waves:snapshot
FROM $baseImage
COPY target /tmp/
RUN apt-get update
RUN apt-get install netcat-traditional
RUN tar zxvf /tmp/consensus-client.tgz -C $WAVES_INSTALL_PATH --strip-components=1
