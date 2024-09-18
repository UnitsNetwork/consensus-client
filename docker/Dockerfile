ARG baseImage=wavesplatform/wavesnode:1.5.7
FROM $baseImage
COPY target /tmp/
RUN tar zxvf /tmp/consensus-client.tgz -C $WAVES_INSTALL_PATH --strip-components=1
