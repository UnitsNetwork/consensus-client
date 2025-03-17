FROM python:3.9

WORKDIR /usr/src/app

ENV PATH=$PATH:/root/.foundry/bin
RUN curl -L https://foundry.paradigm.xyz | bash && foundryup

COPY pyproject.toml ./
RUN pip install --no-cache-dir .

CMD ["/bin/bash", "run.sh"]
