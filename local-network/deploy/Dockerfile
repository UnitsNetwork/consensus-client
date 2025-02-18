FROM python:3.9

WORKDIR /usr/src/app

COPY pyproject.toml ./

RUN pip install --no-cache-dir .

CMD ["/bin/bash", "run.sh"]
