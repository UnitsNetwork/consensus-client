FROM python:3.9

WORKDIR /usr/src/app

ENV PATH=$PATH:/root/.foundry/bin
RUN curl -L https://foundry.paradigm.xyz | bash && foundryup

COPY pyproject.toml ./
RUN pip install --no-cache-dir .

# Foundry uses 0.8.28 by default, but contracts have 0.8.26
# Also, 0.8.26 is not supported on Apple ARM, see https://github.com/foundry-rs/foundry/issues/6665#issuecomment-2101885706
# So we install this manually
RUN solc-select use 0.8.26 --always-install

CMD ["/bin/bash", "run.sh"]
