FROM hyperledger/besu:develop

USER root

RUN apt-get update && apt-get install -y \
    curl \
    netcat \
    iproute2

USER besu
