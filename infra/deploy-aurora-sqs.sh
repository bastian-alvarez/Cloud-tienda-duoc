#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"
STACK_NAME="${STACK_NAME:-tiendaduoc-sqs-aurora}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:4200}"
LAMBDA_EXECUTION_ROLE_ARN="${LAMBDA_EXECUTION_ROLE_ARN:-}"
NOTIFICATION_EMAIL="${NOTIFICATION_EMAIL:-}"

[[ "$STACK_NAME" =~ ^[a-z0-9][a-z0-9-]{0,40}$ ]] || {
  echo "STACK_NAME debe contener minúsculas, números o guiones." >&2
  exit 1
}
[[ "$FRONTEND_ORIGIN" =~ ^https?://[^/]+$ ]] || {
  echo "FRONTEND_ORIGIN debe ser un origen, sin ruta ni barra final." >&2
  exit 1
}
[[ "$NOTIFICATION_EMAIL" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || {
  echo "Define NOTIFICATION_EMAIL con una dirección válida para la suscripción SNS." >&2
  exit 1
}

for command in aws mvn npm python curl; do
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
    --query 'Role.Arn' --output text)"
fi
[[ "$LAMBDA_EXECUTION_ROLE_ARN" == arn:*:iam::*:role/* ]] || {
  echo "LAMBDA_EXECUTION_ROLE_ARN no parece ser un ARN de rol IAM válido." >&2
  exit 1
}

STACK_STATUS="$(aws cloudformation list-stacks \
  --query "StackSummaries[?StackName=='${STACK_NAME}' && StackStatus!='DELETE_COMPLETE'].StackStatus | [0]" \
  --output text)"
case "$STACK_STATUS" in
  ROLLBACK_COMPLETE|CREATE_FAILED|UPDATE_ROLLBACK_FAILED)
    echo "El stack $STACK_NAME está en $STACK_STATUS. Revisa sus recursos y resuelve o elimina ese stack antes de volver a desplegar." >&2
    exit 1
    ;;
esac

ARTIFACT_BUCKET="tiendaduoc-artifacts-${ACCOUNT_ID}-${AWS_REGION}"
BUCKET_EXISTS="$(aws s3api list-buckets \
  --query "Buckets[?Name=='${ARTIFACT_BUCKET}'].Name | [0]" --output text)"
if [[ "$BUCKET_EXISTS" == "None" || -z "$BUCKET_EXISTS" ]]; then
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

echo "Empaquetando los cinco microservicios como artefactos Lambda Java 21..."
mvn -f "$PROJECT_DIR/pom.xml" -Paws-lambda -Dspring-boot.repackage.skip=true clean verify

ARTIFACTS=(
  "services/orders-service/target/orders-service-1.0.0-SNAPSHOT-aws.jar|services/orders-service/orders-service-1.0.0-SNAPSHOT-aws.jar"
  "services/payment-worker/target/payment-worker-1.0.0-SNAPSHOT-aws.jar|services/payment-worker/payment-worker-1.0.0-SNAPSHOT-aws.jar"
  "services/inventory-worker/target/inventory-worker-1.0.0-SNAPSHOT-aws.jar|services/inventory-worker/inventory-worker-1.0.0-SNAPSHOT-aws.jar"
  "services/notification-worker/target/notification-worker-1.0.0-SNAPSHOT-aws.jar|services/notification-worker/notification-worker-1.0.0-SNAPSHOT-aws.jar"
  "services/rabbit-admin-service/target/queue-admin-service-1.0.0-SNAPSHOT-aws.jar|services/queue-admin-service/queue-admin-service-1.0.0-SNAPSHOT-aws.jar"
)
for artifact in "${ARTIFACTS[@]}"; do
  file="${artifact%%|*}"
  key="${artifact#*|}"
  [[ -f "$PROJECT_DIR/$file" ]] || {
    printf 'No se generó el artefacto esperado: %s\n' "$PROJECT_DIR/$file" >&2
    exit 1
  }
  aws s3 cp "$PROJECT_DIR/$file" "s3://${ARTIFACT_BUCKET}/${key}" --only-show-errors
done

if [[ -z "${LAMBDA_EXECUTION_ROLE_ARN:-}" ]]; then
  echo "No se pudo determinar LabRole." >&2
  exit 1
fi

VPC_ID="$(aws ec2 describe-vpcs \
  --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)"
[[ "$VPC_ID" != "None" && -n "$VPC_ID" ]] || {
  echo "No se encontró una VPC predeterminada en $AWS_REGION." >&2
  exit 1
}
read -r -a SUBNET_IDS <<< "$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query 'Subnets | sort_by(@, &AvailabilityZone) | [0:2].SubnetId' --output text)"
[[ "${#SUBNET_IDS[@]}" -ge 2 ]] || {
  echo "Aurora requiere al menos dos subnets en zonas de disponibilidad distintas." >&2
  exit 1
}
SUBNET_LIST="${SUBNET_IDS[0]},${SUBNET_IDS[1]}"

aws cloudformation validate-template --template-body file://infra/aurora-sqs-stack.yaml >/dev/null
aws cloudformation deploy \
  --template-file infra/aurora-sqs-stack.yaml \
  --stack-name "$STACK_NAME" \
  --parameter-overrides \
    "LambdaExecutionRoleArn=$LAMBDA_EXECUTION_ROLE_ARN" \
    "ArtifactBucketName=$ARTIFACT_BUCKET" \
    "NotificationEmail=$NOTIFICATION_EMAIL" \
    "VpcId=$VPC_ID" \
    "SubnetIds=$SUBNET_LIST" \
    "FrontendOrigin=$FRONTEND_ORIGIN" \
  --no-fail-on-empty-changeset

update_lambda_code() {
  local function_name="$1"
  local jar_key="$2"
  local qualified_name="${STACK_NAME}-${function_name}"
  echo "Actualizando código Lambda: $qualified_name"
  aws lambda update-function-code \
    --function-name "$qualified_name" \
    --s3-bucket "$ARTIFACT_BUCKET" \
    --s3-key "$jar_key" \
    --region "$AWS_REGION" >/dev/null
  aws lambda wait function-updated-v2 \
    --function-name "$qualified_name" \
    --region "$AWS_REGION"
}

update_lambda_code "orders-api" "services/orders-service/orders-service-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "payment-worker" "services/payment-worker/payment-worker-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "inventory-worker" "services/inventory-worker/inventory-worker-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "notification-worker" "services/notification-worker/notification-worker-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "payment-results" "services/orders-service/orders-service-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "inventory-results" "services/orders-service/orders-service-1.0.0-SNAPSHOT-aws.jar"
update_lambda_code "queue-admin" "services/queue-admin-service/queue-admin-service-1.0.0-SNAPSHOT-aws.jar"

get_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

API_ENDPOINT="$(get_output ApiEndpoint)"
COGNITO_ISSUER="$(get_output CognitoIssuer)"
COGNITO_CLIENT_ID="$(get_output CognitoClientId)"
COGNITO_DOMAIN="$(get_output CognitoHostedUiDomain)"
FRONTEND_URL="$(get_output FrontendUrl)"
AMPLIFY_APP_ID="$(get_output FrontendAmplifyAppId)"
AMPLIFY_BRANCH="$(get_output FrontendAmplifyBranch)"
FRONTEND_CONFIG="$PROJECT_DIR/frontend/src/app/environment.aws.ts"
cat > "$FRONTEND_CONFIG" <<EOF
export const environment = {
  apiBaseUrl: '${API_ENDPOINT}/api',
  oidcAuthority: '${COGNITO_ISSUER}',
  oidcClientId: '${COGNITO_CLIENT_ID}',
  oidcLogoutEndpoint: '${COGNITO_DOMAIN}/logout',
  appDescription: 'Los pedidos se guardan en Aurora PostgreSQL y el procesamiento asíncrono usa SQS y Lambda.',
  architectureLabel: 'API Gateway · Cognito · Aurora · SQS · Lambda',
};
EOF

echo "Compilando y publicando el frontend Angular en AWS Amplify..."
npm ci --prefix "$PROJECT_DIR/frontend"
npm run build:aws --prefix "$PROJECT_DIR/frontend"
FRONTEND_BUILD_DIR="$PROJECT_DIR/frontend/dist/tiendaduoc-web/browser"
[[ -f "$FRONTEND_BUILD_DIR/index.html" ]] || {
  echo "No se encontró index.html en el resultado de compilación Angular." >&2
  exit 1
}
FRONTEND_ARCHIVE="$(mktemp "${TMPDIR:-/tmp}/tiendaduoc-frontend.XXXXXX.zip")"
trap 'rm -f "$FRONTEND_ARCHIVE"' EXIT
python -c 'import pathlib,sys,zipfile; root=pathlib.Path(sys.argv[1]); archive=zipfile.ZipFile(sys.argv[2],"w",zipfile.ZIP_DEFLATED); [archive.write(path,path.relative_to(root)) for path in root.rglob("*") if path.is_file()]; archive.close()' \
  "$FRONTEND_BUILD_DIR" "$FRONTEND_ARCHIVE"
read -r DEPLOYMENT_JOB_ID ZIP_UPLOAD_URL <<< "$(aws amplify create-deployment \
  --app-id "$AMPLIFY_APP_ID" \
  --branch-name "$AMPLIFY_BRANCH" \
  --query '[jobId,zipUploadUrl]' \
  --output text)"
[[ -n "$DEPLOYMENT_JOB_ID" && -n "$ZIP_UPLOAD_URL" ]] || {
  echo "Amplify no devolvió los datos necesarios para iniciar la publicación." >&2
  exit 1
}
curl --fail --silent --show-error \
  --request PUT \
  --upload-file "$FRONTEND_ARCHIVE" \
  "$ZIP_UPLOAD_URL"
aws amplify start-deployment \
  --app-id "$AMPLIFY_APP_ID" \
  --branch-name "$AMPLIFY_BRANCH" \
  --job-id "$DEPLOYMENT_JOB_ID" >/dev/null

while true; do
  AMPLIFY_STATUS="$(aws amplify get-job \
    --app-id "$AMPLIFY_APP_ID" \
    --branch-name "$AMPLIFY_BRANCH" \
    --job-id "$DEPLOYMENT_JOB_ID" \
    --query 'job.summary.status' \
    --output text)"
  case "$AMPLIFY_STATUS" in
    SUCCEED) break ;;
    FAILED|CANCELLED)
      aws amplify get-job \
        --app-id "$AMPLIFY_APP_ID" \
        --branch-name "$AMPLIFY_BRANCH" \
        --job-id "$DEPLOYMENT_JOB_ID" \
        --query 'job.steps[*].[stepName,status,logUrl]' \
        --output table >&2
      printf 'La publicación de Amplify terminó con estado %s.\n' "$AMPLIFY_STATUS" >&2
      exit 1
      ;;
    *) sleep 10 ;;
  esac
done
curl --fail --silent --show-error --retry 10 --retry-delay 5 \
  "$FRONTEND_URL/" --output /dev/null

printf '\nStack desplegado: %s\n' "$STACK_NAME"
printf 'API Gateway: %s\n' "$API_ENDPOINT"
printf 'Frontend AWS Amplify: %s\n' "$FRONTEND_URL"
printf 'Cognito Hosted UI: %s\n' "$COGNITO_DOMAIN"
printf 'Aurora PostgreSQL: %s (clase db.t3.medium)\n' "$(get_output AuroraEndpoint)"
printf 'SQS de pago: %s\n' "$(get_output PaymentQueueUrl)"
printf 'DLQ de pago: %s\n' "$(get_output PaymentDeadLetterQueueUrl)"
printf 'SQS de inventario: %s\n' "$(get_output InventoryQueueUrl)"
printf 'DLQ de inventario: %s\n' "$(get_output InventoryDeadLetterQueueUrl)"
printf 'SQS de notificaciones: %s\n' "$(get_output NotificationsQueueUrl)"
printf 'DLQ de notificaciones: %s\n' "$(get_output NotificationsDeadLetterQueueUrl)"
printf 'Configuración AWS del frontend generada: %s\n' "$FRONTEND_CONFIG"
printf 'Después de guardar las evidencias, elimina la pila con infra/cleanup-aurora-sqs.sh para detener los cargos.\n'
