#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

command=${1:-help}
if [ "$#" -gt 0 ]; then shift; fi

case "$command" in
  load)
    sha256sum -c FILES.sha256
    docker image load --input images.tar
    ;;
  hash)
    docker run --rm -it --entrypoint java qqbot-pi:__RELEASE_VERSION__ \
      -Dloader.main=dev.qqbot.security.PasswordHash \
      -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  up)
    if [ ! -f .env ]; then
      (umask 077; cp .env.example .env; chmod 600 .env)
      printf '%s\n' 'Created .env. Run: sh deploy.sh hash, fill .env, then run up again.'
      exit 1
    fi
    if grep -Eq '^(ADMIN_PASSWORD_HASH|PI_PROVIDER|PI_MODEL)=.*(replace-with|your-provider|your-model)' .env; then
      printf '%s\n' 'Set ADMIN_PASSWORD_HASH, PI_PROVIDER and PI_MODEL in .env before starting.' >&2
      exit 1
    fi
    docker compose -f compose.yaml up -d --pull never --wait --wait-timeout 150 "$@"
    ;;
  down)
    docker compose -f compose.yaml down "$@"
    ;;
  logs)
    docker compose -f compose.yaml logs --tail 100 "$@"
    ;;
  status)
    docker compose -f compose.yaml ps "$@"
    ;;
  *)
    printf '%s\n' 'Usage: sh deploy.sh {load|hash|up|down|logs|status}'
    ;;
esac
