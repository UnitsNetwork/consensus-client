#!/bin/sh
mkdir -p data/secrets
openssl rand 32 | xxd -p -c 32 > data/secrets/p2p-key
openssl rand 32 | xxd -p -c 32 > data/secrets/jwtsecret
