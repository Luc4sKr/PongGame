#!/bin/sh
set -eu

ENDPOINT="${TF_VAR_aws_endpoint_url:-http://ministack:4566}"
STATE_DIR="/tfstate"
STATE_FILE="${STATE_DIR}/terraform.tfstate"
mkdir -p "${STATE_DIR}"

echo "[terraform-init] Waiting for MiniStack at ${ENDPOINT}/_ministack/health ..."
i=0
until wget -q -O /dev/null "${ENDPOINT}/_ministack/health" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "[terraform-init] Timed out waiting for MiniStack." >&2
    exit 1
  fi
  sleep 1
done
echo "[terraform-init] MiniStack is healthy."

# If MiniStack restarted with fresh in-memory state, clear any stale local tfstate
KEY_CHECK=$(wget -q -O - \
  --header="Content-Type: application/x-amz-json-1.1" \
  --header="X-Amz-Target: TrentService.DescribeKey" \
  --post-data='{"KeyId":"alias/ponggame-jwt-signing"}' \
  "${ENDPOINT}/" 2>/dev/null || true)

if [ -z "${KEY_CHECK}" ] || ! echo "${KEY_CHECK}" | grep -q "KeyMetadata"; then
  if [ -f "${STATE_FILE}" ]; then
    echo "[terraform-init] MiniStack state is fresh; resetting stale Terraform state."
    rm -f "${STATE_FILE}" "${STATE_FILE}.backup"
  fi
fi

terraform apply -auto-approve -no-color -state="${STATE_FILE}"
echo "[terraform-init] Infrastructure provisioned successfully."
terraform output -no-color -state="${STATE_FILE}"
