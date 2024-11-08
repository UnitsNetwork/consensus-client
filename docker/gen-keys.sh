#!/bin/sh
mkdir -p data/secrets
openssl rand -hex 32 | tr -d "\n" > data/secrets/p2p-key
openssl rand -hex 32 | tr -d "\n" > data/secrets/jwtsecret
