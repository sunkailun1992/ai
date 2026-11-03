#!/usr/bin/env bash
set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-http://172.16.1.39:8848}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-cfbf4c42-5ebb-4566-a095-30a568556a85}"
NACOS_USERNAME="${NACOS_USERNAME:-}"
NACOS_PASSWORD="${NACOS_PASSWORD:-}"
NACOS_CONTEXT_PATH="${NACOS_CONTEXT_PATH:-/nacos}"

redact() {
  sed -E 's#([A-Za-z0-9_.-]*(key|secret|password|token|appid|app-id|access-key-id|access-key-secret)[A-Za-z0-9_.-]*:[[:space:]]*).+#\1***REDACTED***#Ig'
}

nacos_curl() {
  curl -sS --connect-timeout 5 --max-time 10 --retry 2 --retry-all-errors --retry-delay 1 "$@"
}

api_path() {
  local path="$1"
  printf '%s%s%s' "${NACOS_ADDR}" "${NACOS_CONTEXT_PATH}" "${path}"
}

detect_version() {
  local response
  local version
  local paths=(
    "/v3/client/server/state"
    "/v3/client/server/version"
    "/v1/console/server/state"
    "/v1/ns/operator/servers"
  )
  for path in "${paths[@]}"; do
    response="$(nacos_curl "$(api_path "${path}")" 2>/dev/null || true)"
    version="$(printf '%s' "${response}" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
    if [[ -n "${version}" ]]; then
      printf '%s' "${version}"
      return 0
    fi
  done
  printf ''
}

major_version() {
  local version="$1"
  if [[ "${version}" =~ ^([0-9]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
    return 0
  fi
  printf '0'
}

login_token() {
  local major="$1"
  if [[ -z "${NACOS_USERNAME}" || -z "${NACOS_PASSWORD}" ]]; then
    return 0
  fi

  local path="/v1/auth/users/login"
  if [[ "${major}" -ge 3 ]]; then
    path="/v3/auth/user/login"
  fi

  nacos_curl \
    -X POST "$(api_path "${path}")" \
    -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

fetch_config_v3() {
  local group="$1"
  local data_id="$2"
  local token="$3"
  local url
  local content
  url="$(api_path "/v3/client/cs/config?dataId=${data_id}&groupName=${group}&namespaceId=${NACOS_NAMESPACE}")"
  printf '\n===== %s / %s =====\n' "${group}" "${data_id}"
  if [[ -n "${token}" ]]; then
    content="$(nacos_curl -H "Authorization: Bearer ${token}" "${url}" || true)"
  else
    content="$(nacos_curl "${url}" || true)"
  fi
  print_config "${data_id}" "${content}"
}

fetch_config_v1() {
  local group="$1"
  local data_id="$2"
  local token="$3"
  local url
  local content
  url="$(api_path "/v1/cs/configs?dataId=${data_id}&group=${group}&tenant=${NACOS_NAMESPACE}")"
  printf '\n===== %s / %s =====\n' "${group}" "${data_id}"
  if [[ -n "${token}" ]]; then
    content="$(nacos_curl -H "Authorization: Bearer ${token}" "${url}" || true)"
  else
    content="$(nacos_curl "${url}" || true)"
  fi
  print_config "${data_id}" "${content}"
}

print_config() {
  local data_id="$1"
  local content="$2"
  printf '%s' "${content}" | redact | sed -n '1,220p'
  check_config_content "${data_id}" "${content}"
}

check_config_content() {
  local data_id="$1"
  local content="$2"
  if [[ -z "${content}" ]]; then
    printf '\nWARN: %s returned empty content or cannot be read.\n' "${data_id}" >&2
    return 0
  fi
  if [[ "${data_id}" != "reuse-configuration.yaml" ]] \
    && printf '%s' "${content}" | grep -Eq '(^|[^0-9])(10\.|172\.(1[6-9]|2[0-9]|3[0-1])\.|192\.168\.)[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)'; then
    printf '\nWARN: %s contains private IP. Move infrastructure addresses to reuse-configuration.yaml and reference custom.infra-* variables.\n' "${data_id}" >&2
  fi
  case "${data_id}" in
    ai.yaml)
      if ! printf '%s' "${content}" | grep -Fq 'custom.infra-nacos-addr'; then
        printf '\nWARN: ai.yaml should set ai.agent-registry.server-addr to ${custom.infra-nacos-addr}.\n' >&2
      fi
      ;;
    ai-agent.yaml)
      if ! printf '%s' "${content}" | grep -Fq 'custom.infra-nacos-addr'; then
        printf '\nWARN: ai-agent.yaml should set ai.prompts.nacos.server-addr to ${custom.infra-nacos-addr}.\n' >&2
      fi
      ;;
  esac
}

fetch_agent_v3() {
  local agent_name="$1"
  local token="$2"
  local url
  url="$(api_path "/v3/admin/ai/a2a?namespaceId=${NACOS_NAMESPACE}&agentName=${agent_name}")"
  printf '\n===== AI Agent / %s =====\n' "${agent_name}"
  if [[ -n "${token}" ]]; then
    nacos_curl -H "Authorization: Bearer ${token}" "${url}" | redact | sed -n '1,220p'
  else
    nacos_curl "${url}" | redact | sed -n '1,220p'
  fi
}

version="$(detect_version)"
major="$(major_version "${version}")"

if [[ -z "${version}" ]]; then
  printf 'Failed to detect Nacos version from %s%s. Check network, context path, and server status first.\n' "${NACOS_ADDR}" "${NACOS_CONTEXT_PATH}" >&2
  exit 2
fi

printf 'Detected Nacos version: %s\n' "${version}"
token="$(login_token "${major}")"

if [[ "${major}" -ge 3 ]]; then
  fetch_config_v3 "DEFAULT_GROUP" "ai.yaml" "${token}"
  fetch_config_v3 "DEFAULT_GROUP" "ai-spring.yaml" "${token}"
  fetch_config_v3 "DEFAULT_GROUP" "ai-agent.yaml" "${token}"
  fetch_config_v3 "DEFAULT_GROUP" "ai-agent-spring.yaml" "${token}"
  fetch_config_v3 "DEFAULT_GROUP" "reuse-configuration.yaml" "${token}"

  fetch_agent_v3 "patient-analysis-agent" "${token}"
  fetch_agent_v3 "questionnaire-agent" "${token}"
else
  fetch_config_v1 "DEFAULT_GROUP" "ai.yaml" "${token}"
  fetch_config_v1 "DEFAULT_GROUP" "ai-spring.yaml" "${token}"
  fetch_config_v1 "DEFAULT_GROUP" "ai-agent.yaml" "${token}"
  fetch_config_v1 "DEFAULT_GROUP" "ai-agent-spring.yaml" "${token}"
  fetch_config_v1 "DEFAULT_GROUP" "reuse-configuration.yaml" "${token}"
  printf '\nNacos AI Registry requires Nacos 3.x. Detected %s, skip AgentCard check.\n' "${version}"
fi
