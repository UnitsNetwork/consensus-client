#!/usr/bin/env bash

if [ -z "$1" ]; then
  echo "Usage: $0 <hex_secret>"
  exit 1
fi

hexsecret=$(echo -n "$1" | tr -d '\n')

base64_url_encode() {
  echo -n "$1" | base64 | sed s/\+/-/g | sed 's/\//_/g' | sed -E s/=+$//
}

jwt_header=$(base64_url_encode '{"alg":"HS256","typ":"JWT"}')

iat=$(date +%s) # Seconds since 1970-01-01
payload=$(base64_url_encode "{\"iat\":${iat}}")

hmac_signature_bin=$(echo -n "${jwt_header}.${payload}" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"${hexsecret}" -binary)
hmac_signature=$(base64_url_encode "$hmac_signature_bin")

echo -n "${jwt_header}.${payload}.${hmac_signature}"
