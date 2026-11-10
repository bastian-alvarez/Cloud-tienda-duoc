#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
STACK_NAME="${STACK_NAME:-tiendaduoc-aws}"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
CREATE_LAMBDA="${CREATE_LAMBDA:-0}"
CREATE_RDS="${CREATE_RDS:-0}"
ALERT_EMAIL="${ALERT_EMAIL:-}"
DB_PASS="${DB_PASS:-}"
LAMBDA_EXECUTION_ROLE_ARN="${LAMBDA_EXECUTION_ROLE_ARN:-}"

[[ "$STACK_NAME" =~ ^[a-z0-9][a-z0-9-]{0,20}$ ]] || {
  echo "STACK_NAME debe tener 1-21 caracteres en minúsculas, números o guiones." >&2
  exit 1
}

for command in aws mvn curl; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Error: falta el comando requerido: %s\n' "$command" >&2
    exit 1
  }
done

[[ "$CREATE_LAMBDA" == "1" ]] || { echo "Define CREATE_LAMBDA=1." >&2; exit 1; }
[[ "$CREATE_RDS" == "1" ]] || { echo "Define CREATE_RDS=1." >&2; exit 1; }
[[ -n "$ALERT_EMAIL" ]] || { echo "Define ALERT_EMAIL." >&2; exit 1; }
if [[ -z "$DB_PASS" ]]; then
  read -r -s -p "Clave maestra para RDS (no se mostrará): " DB_PASS
  printf '\n'
fi
[[ "$DB_PASS" =~ ^[-A-Za-z0-9!#%+._~]{8,41}$ ]] || {
  echo "DB_PASS debe tener entre 8 y 41 caracteres y usar solo letras, números y !#%+._~-." >&2
  exit 1
}

export AWS_REGION
aws sts get-caller-identity >/dev/null
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
if [[ -z "$LAMBDA_EXECUTION_ROLE_ARN" ]]; then
  LAMBDA_EXECUTION_ROLE_ARN="$(aws iam get-role --role-name LabRole \
    --query 'Role.Arn' --output text)" || {
    echo "No se pudo leer el rol existente LabRole. Define LAMBDA_EXECUTION_ROLE_ARN con el ARN de un rol Lambda permitido por Learner Lab." >&2
    exit 1
  }
fi
[[ "$LAMBDA_EXECUTION_ROLE_ARN" == arn:*:iam::*:role/* ]] || {
  echo "LAMBDA_EXECUTION_ROLE_ARN no parece ser un ARN de rol IAM válido." >&2
  exit 1
}
VPC_ID="$(aws ec2 describe-vpcs \
  --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)"
[[ "$VPC_ID" != "None" && -n "$VPC_ID" ]] || {
  echo "No se encontró una VPC predeterminada; crea una VPC/subnets o configura el stack manualmente." >&2
  exit 1
}

read -r -a SUBNET_IDS <<< "$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query 'Subnets | sort_by(@, &AvailabilityZone) | [0:2].SubnetId' --output text)"
[[ "${#SUBNET_IDS[@]}" -ge 2 ]] || {
  echo "RDS requiere al menos dos subnets en la VPC predeterminada." >&2
  exit 1
}

if [[ -z "${DB_CLIENT_CIDR:-}" ]]; then
  PUBLIC_IP="$(curl --fail --silent --show-error https://checkip.amazonaws.com | tr -d '[:space:]')"
  DB_CLIENT_CIDR="${PUBLIC_IP}/32"
fi
[[ "$DB_CLIENT_CIDR" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/32$ ]] || {
  echo "DB_CLIENT_CIDR debe ser una IPv4 con máscara /32, por ejemplo 203.0.113.10/32." >&2
  exit 1
}

ARTIFACT_BUCKET="tiendaduoc-lambda-${ACCOUNT_ID}-${AWS_REGION}-${STACK_NAME}"
ARTIFACT_KEY="lambda-email.jar"

echo "Empaquetando la Lambda y sus dependencias..."
mvn -f "$PROJECT_DIR/pom.xml" -pl lambda-email -am clean package
LAMBDA_JAR="$PROJECT_DIR/lambda-email/target/lambda-email-1.0.0-SNAPSHOT.jar"
[[ -f "$LAMBDA_JAR" ]] || { echo "No se generó el JAR: $LAMBDA_JAR" >&2; exit 1; }

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
aws s3 cp "$LAMBDA_JAR" "s3://${ARTIFACT_BUCKET}/${ARTIFACT_KEY}"

SUBNET_LIST="$(IFS=,; printf '%s' "${SUBNET_IDS[*]}")"
aws cloudformation deploy \
  --template-file "$SCRIPT_DIR/stack.yaml" \
  --stack-name "$STACK_NAME" \
  --parameter-overrides \
    "AlertEmail=$ALERT_EMAIL" \
    "DbPassword=$DB_PASS" \
    "ClientCidr=$DB_CLIENT_CIDR" \
    "DefaultVpcId=$VPC_ID" \
    "SubnetIds=$SUBNET_LIST" \
    "LambdaCodeBucket=$ARTIFACT_BUCKET" \
    "LambdaCodeKey=$ARTIFACT_KEY" \
    "LambdaExecutionRoleArn=$LAMBDA_EXECUTION_ROLE_ARN" \
  --no-fail-on-empty-changeset

get_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

printf '\nInfraestructura lista. Confirma la suscripción SNS enviada a %s.\n' "$ALERT_EMAIL"
printf 'Copie estos exports en la terminal donde ejecutarás pedidos-service:\n'
printf 'export AWS_REGION=%q\n' "$AWS_REGION"
printf 'export DB_URL=%q\n' "$(get_output DatabaseUrl)"
printf 'export DB_USERNAME=%q\n' "$(get_output DatabaseUsername)"
printf 'export ORDERS_QUEUE_URL=%q\n' "$(get_output OrdersQueueUrl)"
printf 'export PAYMENT_RESULTS_QUEUE_URL=%q\n' "$(get_output PaymentResultsQueueUrl)"
printf 'export DB_PASSWORD="$DB_PASS"  # conserva DB_PASS en esta terminal\n'
printf '\nComprobante S3: %s\n' "$(get_output ReceiptBucketName)"
printf 'Alarma CloudWatch: %s\n' "$(get_output DeadLetterAlarmName)"
printf 'Stack: %s (para eliminarlo: bash ./cleanup.sh)\n' "$STACK_NAME"
if command -v cygpath >/dev/null 2>&1; then
  POWERSHELL_SERVICE_DIR="$(cygpath -w "$PROJECT_DIR/services/orders-service")"
  printf '\nComandos PowerShell para pedidos-service (ingresa la clave RDS cuando se solicite):\n'
  printf '$env:AWS_REGION = "%s"\n' "$AWS_REGION"
  printf '$env:DB_URL = "%s"\n' "$(get_output DatabaseUrl)"
  printf '$env:DB_USERNAME = "%s"\n' "$(get_output DatabaseUsername)"
  printf '$env:ORDERS_QUEUE_URL = "%s"\n' "$(get_output OrdersQueueUrl)"
  printf '$env:PAYMENT_RESULTS_QUEUE_URL = "%s"\n' "$(get_output PaymentResultsQueueUrl)"
  printf '$secureDbPass = Read-Host "Clave RDS" -AsSecureString\n'
  printf '$dbPassPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureDbPass)\n'
  printf 'try { $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbPassPtr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbPassPtr) }\n'
  printf 'cd "%s"\n' "$POWERSHELL_SERVICE_DIR"
  printf 'mvn spring-boot:run "-Dspring-boot.run.profiles=aws"\n'
fi
