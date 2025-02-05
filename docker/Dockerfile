ARG baseImage=ghcr.io/wavesplatform/waves:scala-3
FROM $baseImage
COPY target /tmp/
RUN tar zxvf /tmp/consensus-client.tgz -C $WAVES_INSTALL_PATH --strip-components=1
