#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
STACK_NAME="${STACK_NAME:-tiendaduoc-serverless}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
ALERT_EMAIL="${ALERT_EMAIL:-}"
FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:4200}"
LAMBDA_EXECUTION_ROLE_ARN="${LAMBDA_EXECUTION_ROLE_ARN:-}"

[[ "$STACK_NAME" =~ ^[a-z0-9][a-z0-9-]{0,20}$ ]] || {
  echo "STACK_NAME debe tener 1-21 caracteres en minúsculas, números o guiones." >&2
  exit 1
}
[[ -n "$ALERT_EMAIL" ]] || {
  echo "Define ALERT_EMAIL con el correo que recibirá las notificaciones SNS." >&2
  exit 1
}
[[ "$FRONTEND_ORIGIN" =~ ^https?://[^/]+$ ]] || {
  echo "FRONTEND_ORIGIN debe ser un origen, sin ruta ni barra final; por ejemplo http://localhost:4200." >&2
  exit 1
}

for command in aws mvn; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Error: falta el comando requerido: %s\n' "$command" >&2
    exit 1
  }
done

export AWS_REGION
aws sts get-caller-identity >/dev/null
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
if [[ -z "$LAMBDA_EXECUTION_ROLE_ARN" ]]; then
  LAMBDA_EXECUTION_ROLE_ARN="$(aws iam get-role --role-name LabRole \
    --query 'Role.Arn' --output text)" || {
    echo "No se pudo leer LabRole. Define LAMBDA_EXECUTION_ROLE_ARN con un rol Lambda existente permitido por Learner Lab." >&2
    exit 1
  }
fi
[[ "$LAMBDA_EXECUTION_ROLE_ARN" == arn:*:iam::*:role/* ]] || {
  echo "LAMBDA_EXECUTION_ROLE_ARN no parece ser un ARN de rol IAM válido." >&2
  exit 1
}

ARTIFACT_BUCKET="tiendaduoc-lambda-${ACCOUNT_ID}-${AWS_REGION}-${STACK_NAME}"
ARTIFACT_KEY="tiendaduoc-serverless-lambda.jar"
TEMPLATE="$SCRIPT_DIR/serverless-stack.yaml"
if command -v cygpath >/dev/null 2>&1; then
  TEMPLATE_CLI_PATH="$(cygpath -w "$TEMPLATE")"
else
  TEMPLATE_CLI_PATH="$TEMPLATE"
fi

echo "Empaquetando las funciones Lambda Java 21..."
mvn -f "$PROJECT_DIR/pom.xml" -pl lambda-email -am clean verify
LAMBDA_JAR="$PROJECT_DIR/lambda-email/target/lambda-email-1.0.0-SNAPSHOT.jar"
[[ -f "$LAMBDA_JAR" ]] || {
  echo "No se generó el JAR de Lambda: $LAMBDA_JAR" >&2
  exit 1
}

if ! aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null 2>&1; then
  if [[ "$AWS_REGION" == "us-east-1" ]]; then
    aws s3api create-bucket --bucket "$ARTIFACT_BUCKET" >/dev/null
  else
    aws s3api create-bucket --bucket "$ARTIFACT_BUCKET" \
      --create-bucket-configuration "LocationConstraint=$AWS_REGION" >/dev/null
  fi
fi
aws s3api put-public-access-block --bucket "$ARTIFACT_BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$ARTIFACT_BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3 cp "$LAMBDA_JAR" "s3://${ARTIFACT_BUCKET}/${ARTIFACT_KEY}"

STACK_STATUS="$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"
if [[ "$STACK_STATUS" == "ROLLBACK_COMPLETE" ]]; then
  echo "El stack anterior quedó en ROLLBACK_COMPLETE sin recursos activos; eliminando solo su registro para permitir el nuevo despliegue."
  aws cloudformation delete-stack --stack-name "$STACK_NAME"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"
fi

TEMPLATE_BODY="$(cat "$TEMPLATE")"
aws cloudformation validate-template --template-body "$TEMPLATE_BODY" >/dev/null
aws cloudformation deploy \
  --template-file "$TEMPLATE_CLI_PATH" \
  --stack-name "$STACK_NAME" \
  --parameter-overrides \
    "AlertEmail=$ALERT_EMAIL" \
    "FrontendOrigin=$FRONTEND_ORIGIN" \
    "LambdaCodeBucket=$ARTIFACT_BUCKET" \
    "LambdaCodeKey=$ARTIFACT_KEY" \
    "LambdaExecutionRoleArn=$LAMBDA_EXECUTION_ROLE_ARN" \
  --no-fail-on-empty-changeset

get_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

API_ENDPOINT="$(get_output ApiEndpoint)"
COGNITO_ISSUER="$(get_output CognitoIssuer)"
COGNITO_CLIENT_ID="$(get_output CognitoClientId)"
COGNITO_DOMAIN="$(get_output CognitoHostedUiDomain)"
FRONTEND_CONFIG="$PROJECT_DIR/frontend/src/app/environment.aws.ts"
cat > "$FRONTEND_CONFIG" <<EOF
export const environment = {
  apiBaseUrl: '${API_ENDPOINT}/api',
  oidcAuthority: '${COGNITO_ISSUER}',
  oidcClientId: '${COGNITO_CLIENT_ID}',
  oidcLogoutEndpoint: '${COGNITO_DOMAIN}/logout',
  appDescription: 'Los pedidos se guardan en DynamoDB y el pago se procesa en segundo plano con SQS y Lambda.',
  architectureLabel: 'API Gateway · Cognito · DynamoDB · Lambda',
};
EOF

printf '\nStack serverless desplegado: %s\n' "$STACK_NAME"
printf 'API Gateway: %s\n' "$API_ENDPOINT"
printf 'Cognito Hosted UI: %s\n' "$COGNITO_DOMAIN"
printf 'Cognito User Pool ID: %s\n' "$(get_output CognitoUserPoolId)"
printf 'Tabla DynamoDB de pedidos: %s\n' "$(get_output OrdersTableName)"
printf 'Comprobante S3: %s\n' "$(get_output ReceiptBucketName)"
printf 'DLQ SQS: %s\n' "$(get_output OrdersDlqUrl)"
printf 'Alarma CloudWatch: %s\n' "$(get_output DeadLetterAlarmName)"
printf 'Suscríbete/Confirma el correo SNS enviado a %s.\n' "$ALERT_EMAIL"
printf 'Se generó la configuración local del frontend: %s\n' "$FRONTEND_CONFIG"
printf 'Inicia la interfaz AWS en otra terminal: cd "%s/frontend"; npm run start:aws\n' "$PROJECT_DIR"
printf 'La cuenta Cognito permite crear usuarios desde la pantalla de login; usa un correo tuyo y confirma su verificación.\n'

if aws cloudformation describe-stacks --stack-name tiendaduoc-aws >/dev/null 2>&1; then
  printf '\nAviso: el stack anterior tiendaduoc-aws sigue activo y puede generar costos. No se modificó ni borró.\n'
  printf 'Cuando verifiques el modo serverless, elimina el stack anterior con: STACK_NAME=tiendaduoc-aws bash "%s/cleanup.sh"\n' "$SCRIPT_DIR"
fi

cat <<'EOF'

Permisos requeridos en el rol de ejecución existente (LabRole):
- logs:CreateLogGroup, logs:CreateLogStream, logs:PutLogEvents
- dynamodb:PutItem, dynamodb:GetItem, dynamodb:Scan, dynamodb:UpdateItem sobre la tabla de pedidos
- sqs:SendMessage, sqs:ReceiveMessage, sqs:DeleteMessage, sqs:ChangeMessageVisibility, sqs:GetQueueAttributes
- s3:PutObject, sns:Publish

Si CloudFormation falla, revisa el primer recurso CREATE_FAILED y no abras permisos a toda la cuenta.
DynamoDB está en modo bajo demanda; se cobra por las solicitudes y almacenamiento. Ejecuta cleanup-serverless.sh al terminar.
EOF
