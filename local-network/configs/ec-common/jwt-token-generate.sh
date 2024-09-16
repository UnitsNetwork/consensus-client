#!/usr/bin/env bash

if [ -z "$1" ]; then
  echo "Usage: $0 <hex_secret>"
  exit 1
fi

hexsecret=$(echo -n "$1" | tr -d '\n')

# Construct the header
jwt_header=$(echo -n '{"alg":"HS256","typ":"JWT"}' | base64 | sed s/\+/-/g | sed 's/\//_/g' | sed -E s/=+$//)

# Get the current Unix timestamp (seconds since 1970-01-01)
iat=$(date +%s)

# Construct the payload with 'iat' claim
payload=$(echo -n "{\"iat\":${iat}}" | base64 | sed s/\+/-/g | sed 's/\//_/g' |  sed -E s/=+$//)

# Calculate hmac signature -- note option to pass in the key as hex bytes
hmac_signature=$(echo -n "${jwt_header}.${payload}" | openssl dgst -sha256 -mac HMAC -macopt hexkey:$hexsecret -binary | base64 | sed s/\+/-/g | sed 's/\//_/g' | sed -E s/=+$//)

# Create the full token
jwt="${jwt_header}.${payload}.${hmac_signature}"

# Output the generated JWT token
echo -n $jwt
