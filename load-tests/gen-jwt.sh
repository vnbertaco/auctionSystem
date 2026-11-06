#!/usr/bin/env bash
# Gera JWT HS256 de teste (simulação de autenticação, Princípio VI).
# Uso: JWT_SECRET=... ./gen-jwt.sh u1      -> imprime o token com sub=u1
set -euo pipefail

SUB="${1:?uso: gen-jwt.sh <user_id>}"
SECRET="${JWT_SECRET:?defina JWT_SECRET}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
NOW=$(date +%s)
PAYLOAD=$(printf '{"sub":"%s","iat":%d,"exp":%d}' "$SUB" "$NOW" "$((NOW + 3600))" | b64url)
SIG=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIG"
