FROM python:3.12-slim 

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    git \
    gcc \
    libc6-dev \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

WORKDIR /usr/src/app

COPY local/__init__.py ./local/
COPY pyproject.toml ./

RUN pip install --no-cache-dir --editable .

CMD ["/bin/bash", "run.sh"]
