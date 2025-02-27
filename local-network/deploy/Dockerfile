FROM python:3.9

WORKDIR /usr/src/app

COPY pyproject.toml ./

RUN curl -L https://foundry.paradigm.xyz | bash
ENV PATH=$PATH:/root/.foundry/bin
RUN foundryup

RUN pip install --no-cache-dir --editable .

CMD ["/bin/bash", "run.sh"]
