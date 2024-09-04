FROM node:18-bullseye-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    curl \
    && pip3 install solc-select \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

WORKDIR /home/node/app
COPY package.json package-lock.json /home/node/app/
RUN npm i

CMD ["/bin/bash", "deploy-run.sh"]
