#!/usr/bin/env bash
set -euo pipefail

STACK_NAME="${STACK_NAME:-tiendaduoc-aws}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
export AWS_REGION

[[ "$STACK_NAME" =~ ^[a-z0-9][a-z0-9-]{0,20}$ ]] || {
  echo "STACK_NAME debe tener 1-21 caracteres en minúsculas, números o guiones." >&2
  exit 1
}

if ! aws cloudformation describe-stacks --stack-name "$STACK_NAME" >/dev/null 2>&1; then
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
  ARTIFACT_BUCKET="tiendaduoc-lambda-${ACCOUNT_ID}-${AWS_REGION}-${STACK_NAME}"
  if aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null 2>&1; then
    aws s3 rm "s3://${ARTIFACT_BUCKET}" --recursive
    aws s3api delete-bucket --bucket "$ARTIFACT_BUCKET"
    echo "No existía el stack; se eliminó el bucket temporal $ARTIFACT_BUCKET."
  else
    echo "No existe el stack $STACK_NAME ni su bucket temporal; no se borró ningún recurso."
  fi
  exit 0
fi

STACK_STATUS="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query 'Stacks[0].StackStatus' --output text)"
RECEIPT_BUCKET="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='ReceiptBucketName'].OutputValue | [0]" --output text)"
ARTIFACT_BUCKET="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='LambdaArtifactBucketName'].OutputValue | [0]" --output text)"
RETAINED_DB_IDENTIFIER=""
ROLLBACK_DB_IDENTIFIER=""
if [[ "$STACK_STATUS" == "ROLLBACK_FAILED" ]]; then
  ROLLBACK_DB_IDENTIFIER="$(aws cloudformation describe-stack-resource \
    --stack-name "$STACK_NAME" \
    --logical-resource-id OrdersDatabase \
    --query 'StackResourceDetail.PhysicalResourceId' --output text)"
fi

echo "Eliminando el stack $STACK_NAME (RDS puede tardar varios minutos)..."
aws cloudformation delete-stack --stack-name "$STACK_NAME"
if ! aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"; then
  DELETE_STATUS="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query 'Stacks[0].StackStatus' --output text)"
  FAILED_DB_STATUS="$(aws cloudformation describe-stack-resource \
    --stack-name "$STACK_NAME" \
    --logical-resource-id OrdersDatabase \
    --query 'StackResourceDetail.ResourceStatus' --output text)"
  if [[ "$DELETE_STATUS" != "DELETE_FAILED" || "$FAILED_DB_STATUS" != "DELETE_FAILED" ]]; then
    echo "El stack no se pudo borrar automáticamente (estado: $DELETE_STATUS; OrdersDatabase: $FAILED_DB_STATUS). Revisa los eventos de CloudFormation." >&2
    exit 1
  fi

  if [[ "$ROLLBACK_DB_IDENTIFIER" == "None" || -z "$ROLLBACK_DB_IDENTIFIER" ]]; then
    ROLLBACK_DB_IDENTIFIER="$(aws cloudformation describe-stack-resource \
      --stack-name "$STACK_NAME" \
      --logical-resource-id OrdersDatabase \
      --query 'StackResourceDetail.PhysicalResourceId' --output text)"
  fi
  echo "RDS impidió eliminar el stack; se conservará temporalmente para completar el borrado."
  aws cloudformation delete-stack --stack-name "$STACK_NAME" --retain-resources OrdersDatabase
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"
  RETAINED_DB_IDENTIFIER="$ROLLBACK_DB_IDENTIFIER"
fi

if [[ "$RETAINED_DB_IDENTIFIER" != "None" && -n "$RETAINED_DB_IDENTIFIER" ]]; then
  DB_STATUS="$(aws rds describe-db-instances --db-instance-identifier "$RETAINED_DB_IDENTIFIER" \
    --query 'DBInstances[0].DBInstanceStatus' --output text)"
  if [[ "$DB_STATUS" != "None" && -n "$DB_STATUS" ]]; then
    if [[ "$DB_STATUS" != "available" ]]; then
      echo "Esperando que RDS esté disponible para eliminarla (estado: $DB_STATUS)..."
      aws rds wait db-instance-available --db-instance-identifier "$RETAINED_DB_IDENTIFIER"
    fi
    aws rds delete-db-instance \
      --db-instance-identifier "$RETAINED_DB_IDENTIFIER" \
      --skip-final-snapshot \
      --delete-automated-backups
    aws rds wait db-instance-deleted --db-instance-identifier "$RETAINED_DB_IDENTIFIER"
  fi
fi

if [[ "$RECEIPT_BUCKET" != "None" && -n "$RECEIPT_BUCKET" ]]; then
  aws s3 rm "s3://${RECEIPT_BUCKET}" --recursive
fi

if [[ "$ARTIFACT_BUCKET" != "None" && -n "$ARTIFACT_BUCKET" ]]; then
  aws s3 rm "s3://${ARTIFACT_BUCKET}" --recursive
  aws s3api delete-bucket --bucket "$ARTIFACT_BUCKET"
else
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
  ARTIFACT_BUCKET="tiendaduoc-lambda-${ACCOUNT_ID}-${AWS_REGION}-${STACK_NAME}"
  if aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null 2>&1; then
    aws s3 rm "s3://${ARTIFACT_BUCKET}" --recursive
    aws s3api delete-bucket --bucket "$ARTIFACT_BUCKET"
  fi
fi

echo "Stack y bucket temporal eliminados. Verifica AWS Academy para confirmar que no quedan recursos."
