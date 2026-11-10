#!/usr/bin/env bash
set -euo pipefail

STACK_NAME="${STACK_NAME:-tiendaduoc-sqs-aurora}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
export AWS_REGION

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ARTIFACT_BUCKET="tiendaduoc-artifacts-${ACCOUNT_ID}-${AWS_REGION}"
STACK_STATUS="$(aws cloudformation list-stacks \
  --query "StackSummaries[?StackName=='${STACK_NAME}' && StackStatus!='DELETE_COMPLETE'].StackStatus | [0]" \
  --output text)"
if [[ "$STACK_STATUS" == "None" || -z "$STACK_STATUS" ]]; then
  echo "No existe una pila activa llamada $STACK_NAME."
  exit 0
fi

echo "Se eliminarán Aurora PostgreSQL, colas SQS y DLQ, Lambdas, API Gateway, Cognito y la configuración de red de $STACK_NAME."
echo "La eliminación de Aurora borra permanentemente sus datos."
aws cloudformation delete-stack --stack-name "$STACK_NAME"
aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"

for key in \
  services/orders-service/orders-service-1.0.0-SNAPSHOT-aws.jar \
  services/payment-worker/payment-worker-1.0.0-SNAPSHOT-aws.jar \
  services/inventory-worker/inventory-worker-1.0.0-SNAPSHOT-aws.jar \
  services/notification-worker/notification-worker-1.0.0-SNAPSHOT-aws.jar \
  services/queue-admin-service/queue-admin-service-1.0.0-SNAPSHOT-aws.jar; do
  aws s3api delete-object --bucket "$ARTIFACT_BUCKET" --key "$key" >/dev/null
done

echo "Pila eliminada y solo los cinco JAR AWS de este despliegue fueron retirados del bucket."
