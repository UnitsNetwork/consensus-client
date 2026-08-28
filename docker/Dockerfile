ARG baseImage=ghcr.io/wavesplatform/waves:1.6.4
FROM $baseImage
RUN --mount=type=bind,source=target,target=/tmp/ \
  tar zxvf /tmp/consensus-client.tgz -C $WAVES_INSTALL_PATH --strip-components=1
