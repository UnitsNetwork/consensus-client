FROM python:3.9

WORKDIR /usr/src/app

ENV PATH=$PATH:/root/.foundry/bin
RUN curl -L https://foundry.paradigm.xyz | bash && foundryup

COPY pyproject.toml ./
RUN pip install --no-cache-dir .

# 0.8.29 is not supported on Apple ARM, see https://github.com/foundry-rs/foundry/issues/6665#issuecomment-2101885706
# So we install this manually
RUN solc-select use 0.8.29 --always-install

CMD ["/bin/bash", "run.sh"]
