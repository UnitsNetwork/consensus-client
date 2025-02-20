FROM python:3.9

WORKDIR /usr/src/app

COPY pyproject.toml ./

RUN pip install --no-cache-dir --editable .
RUN curl -L https://foundry.paradigm.xyz | bash
ENV PATH=$PATH:/root/.foundry/bin
RUN foundryup

CMD ["/bin/bash", "run.sh"]

# FROM ghcr.io/foundry-rs/foundry:latest
#
# RUN apt-get update
# RUN apt-get install -y --no-install-recommends build-essential git curl wget ca-certificates
#
# RUN cd /tmp && \
#   wget https://go.dev/dl/go1.24.0.linux-amd64.tar.gz && \
#   tar -xf go1.24.0.linux-amd64.tar.gz
# ENV PATH=$PATH:/tmp/go/bin
#
# RUN cd /tmp && \
#   git clone --depth 1 https://github.com/wavesplatform/gowaves.git && \
#   cd gowaves && \
#   make build-convert-linux
#
# ENV PATH=$PATH:/tmp/gowaves/build/bin/linux-amd64
