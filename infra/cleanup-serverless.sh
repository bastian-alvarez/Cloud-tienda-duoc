#!/usr/bin/env bash
set -euo pipefail

STACK_NAME="${STACK_NAME:-tiendaduoc-serverless}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
export AWS_REGION

[[ "$STACK_NAME" =~ ^[a-z0-9][a-z0-9-]{0,20}$ ]] || {
  echo "STACK_NAME debe tener 1-21 caracteres en minúsculas, números o guiones." >&2
  exit 1
}

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ARTIFACT_BUCKET="tiendaduoc-lambda-${ACCOUNT_ID}-${AWS_REGION}-${STACK_NAME}"
if ! aws cloudformation describe-stacks --stack-name "$STACK_NAME" >/dev/null 2>&1; then
  if aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null 2>&1; then
    aws s3 rm "s3://${ARTIFACT_BUCKET}" --recursive
    aws s3api delete-bucket --bucket "$ARTIFACT_BUCKET"
    echo "No existía el stack; se eliminó el bucket de artefactos $ARTIFACT_BUCKET."
  else
    echo "No existe el stack $STACK_NAME ni su bucket de artefactos."
  fi
  exit 0
fi

RECEIPT_BUCKET="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='ReceiptBucketName'].OutputValue | [0]" --output text)"
if [[ "$RECEIPT_BUCKET" != "None" && -n "$RECEIPT_BUCKET" ]]; then
  aws s3 rm "s3://${RECEIPT_BUCKET}" --recursive
fi

echo "Eliminando $STACK_NAME, incluidas API Gateway, Cognito y DynamoDB. Esta acción elimina la tabla y los pedidos."
aws cloudformation delete-stack --stack-name "$STACK_NAME"
if ! aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"; then
  echo "No se pudo borrar automáticamente el stack. Revisa los eventos de CloudFormation; no borres recursos al azar." >&2
  exit 1
fi

if aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null 2>&1; then
  aws s3 rm "s3://${ARTIFACT_BUCKET}" --recursive
  aws s3api delete-bucket --bucket "$ARTIFACT_BUCKET"
fi

echo "Stack serverless y bucket de artefactos eliminados. Verifica AWS Academy para confirmar que no quedan recursos."
