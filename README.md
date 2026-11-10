# TiendaDuoc: microservicios y arquitectura AWS

Proyecto académico de pedidos con frontend Angular, microservicios Java 21/Spring Boot, PostgreSQL, autenticación Cognito y procesamiento asíncrono con Amazon SQS y Lambda.

Para entender el flujo desplegado en AWS y el propósito de cada servicio, consulta [Flujo del proyecto TiendaDuoc en AWS](flujo-proyecto-aws.md).

## Alcance de la arquitectura

- **Local:** PostgreSQL, LocalStack SQS y Keycloak mediante Docker Compose; los cinco servicios Java se ejecutan como procesos locales.
- **AWS:** Amplify Hosting publica el frontend Angular por HTTPS; S3 almacena los JAR; API Gateway HTTP API y Cognito protegen la API; Lambda ejecuta los handlers Java; Aurora PostgreSQL guarda los datos y crea/actualiza los esquemas con Hibernate `ddl-auto: update`; SQS desacopla los workers y sus colas de resultados, cada una con su DLQ.
- **Sin EC2 ni RabbitMQ administrado en AWS.** La cuenta Learner Lab no permite Amazon MQ y se decidió no crear EC2. SQS es una alternativa funcional para colas y DLQ, pero **no sustituye la topología RabbitMQ ni cumple literalmente una rúbrica que exija RabbitMQ**.
- La plantilla `infra/aurora-sqs-stack.yaml` define la infraestructura; su presencia en el repositorio no refleja por sí sola el estado de AWS.

En esta sesión se desplegó `tiendaduoc-sqs-aurora` en `us-east-1` y se verificó `CREATE_COMPLETE`, Aurora privada y el procesamiento de pedidos hasta `COMPLETADO`. La instancia sigue generando cargos mientras la pila permanezca activa; el estado puede cambiar después de esta sesión.

## Componentes

| Componente | Responsabilidad |
|---|---|
| `orders-service` | API de pedidos, persistencia en el esquema PostgreSQL `orders` y publicación de eventos a SQS. |
| `payment-worker` | Procesa eventos de pago y persiste idempotencia en `payment`; publica el resultado. |
| `inventory-worker` | Procesa inventario y persiste idempotencia en `inventory`; publica el resultado. |
| `notification-worker` | Procesa notificaciones simuladas. |
| `rabbit-admin-service` | Conserva esa ruta/nombre histórico del módulo; el artefacto AWS administra colas SQS, no RabbitMQ. |
| `lambda-email` | Módulo legado; no forma parte del despliegue Aurora/SQS actual. |
| `frontend` | Angular 20 con Keycloak local o Cognito en AWS mediante OIDC/PKCE. |

Los pagos, las reservas y las notificaciones son simulaciones. No se realizan cobros reales ni deben usarse datos personales reales.

## Requisitos

- JDK 21 y Maven 3.9+
- Node.js 20+ y npm
- Docker Desktop con Docker Compose
- AWS CLI v2 y una sesión vigente de AWS Academy Learner Lab para desplegar
- En Windows, ejecutar los scripts `.sh` desde Git Bash o WSL

## Compilar y probar

Desde la raíz:

```bash
mvn clean verify
```

Para empaquetar los cinco artefactos Lambda Java 21:

```bash
mvn -Paws-lambda -Dspring-boot.repackage.skip=true clean verify
```

Los JAR sombreados usan el sufijo `-aws.jar`.

## Ejecutar localmente

```bash
docker compose up -d
docker compose ps
```

Compose levanta PostgreSQL (`localhost:5432`), LocalStack SQS (`localhost:4566`) y Keycloak (`localhost:8180`). Keycloak es solo para desarrollo local; sus credenciales locales no deben reutilizarse en AWS.

Desde la raíz, inicia cada servicio en una terminal:

```bash
java -jar services/orders-service/target/orders-service-1.0.0-SNAPSHOT.jar
java -jar services/payment-worker/target/payment-worker-1.0.0-SNAPSHOT.jar
java -jar services/inventory-worker/target/inventory-worker-1.0.0-SNAPSHOT.jar
java -jar services/notification-worker/target/notification-worker-1.0.0-SNAPSHOT.jar
java -jar services/rabbit-admin-service/target/queue-admin-service-1.0.0-SNAPSHOT.jar
```

En otra terminal, inicia el frontend:

```bash
cd frontend
npm ci
npm start
```

Abre <http://localhost:4200>. Los servicios crean/actualizan sus tablas con Hibernate. Las rutas de pedidos y administración están documentadas en `infra/pruebas.http`; el flujo AWS y el uso de las colas se describen en [`implementacion-y-arquitectura.md`](implementacion-y-arquitectura.md).

## Desplegar en AWS Academy

Inicia el Learner Lab, configura sus credenciales temporales de AWS CLI sin agregarlas al repositorio y comprueba la identidad:

```bash
aws sts get-caller-identity
```

El despliegue crea Aurora PostgreSQL **db.t3.medium**, Lambda, API Gateway, Cognito, SQS, Amplify Hosting y recursos de red; sube cinco JAR al bucket privado S3 y publica el frontend Angular en Amplify. No crea EC2. Aurora db.t3.medium y otros servicios pueden generar cargos mientras la pila exista, aunque no haya pedidos. Usa datos de prueba y revisa créditos/cuotas antes de continuar.

Desde Git Bash o WSL:

```bash
export AWS_REGION=us-east-1
export NOTIFICATION_EMAIL=tu-correo@example.com
cd infra
bash ./deploy-aurora-sqs.sh
```

El script compila y prueba los microservicios, sube los JAR, despliega CloudFormation, actualiza las siete Lambdas, compila Angular con la configuración de AWS y publica el paquete en Amplify Hosting. `NOTIFICATION_EMAIL` será suscrito al tema SNS y deberá confirmar la suscripción desde el correo que SNS envíe. Al finalizar imprime las URLs de API y frontend. `frontend/src/app/environment.aws.ts` se genera localmente durante la publicación, pero el sitio queda servido desde AWS y no requiere ejecutar un servidor frontend en tu computador.

### Publicación alternativa del frontend en GitHub Pages

El frontend también se publica automáticamente en [GitHub Pages](https://bastian-alvarez.github.io/Cloud-tienda-duoc/) cuando se actualiza `main`. Pages aloja solo los archivos estáticos de Angular; autenticación, pedidos y procesamiento siguen dependiendo de Cognito y los servicios AWS desplegados. El flujo de GitHub Actions construye con `npm run build:github-pages`, configura la subruta del repositorio y publica un `404.html` para restaurar rutas de la aplicación.

La plantilla CloudFormation agrega el origen de Pages a CORS de API Gateway y registra las URL de callback y cierre de sesión de Cognito. Para que el inicio de sesión desde Pages funcione, actualiza la pila `tiendaduoc-sqs-aurora` con `infra/deploy-aurora-sqs.sh` antes de usar esa versión del frontend. Amplify continúa disponible sin cambios.

Abre la URL **Frontend AWS Amplify** que imprime el script y registra un usuario Cognito de prueba. Los endpoints de administración SQS son `GET/POST /api/admin/queues` y `DELETE /api/admin/queues/{queueName}`. Solo los usuarios del grupo Cognito `QueueAdministrators` pueden usarlos. Tras desplegar, agrega explícitamente a tu usuario de prueba a ese grupo desde Cognito; el registro normal no concede privilegios administrativos.

### Costos y eliminación

Aurora provisionada es el recurso con mayor costo continuo; Amplify Hosting, funciones, API Gateway, SQS, endpoints VPC, Cognito, almacenamiento y transferencia también pueden generar cargos. Detén el gasto eliminando la pila cuando termines y después de guardar tus evidencias:

```bash
cd infra
bash ./cleanup-aurora-sqs.sh
```

La eliminación de CloudFormation borra permanentemente la base Aurora y sus datos. El script también elimina únicamente los cinco JAR de esta implementación del bucket; no borra el bucket ni otros artefactos.

## Diferencia con la rúbrica

Esta variante usa Amazon SQS con dead-letter queues en vez de RabbitMQ/Amazon MQ, y reemplaza el servidor de administración Rabbit por endpoints de administración SQS. Si el evaluador exige RabbitMQ, exchanges/routing keys, consola Rabbit o Amazon MQ, esos puntos siguen pendientes y la sustitución debe explicarse; no presentar SQS como si fuera RabbitMQ.

## Seguridad y cuidado

- No subas `.env`, credenciales de AWS, tokens, contraseñas ni `environment.aws.ts`.
- No expongas Aurora a Internet; el template la configura como privada y limita PostgreSQL al grupo de seguridad de las Lambdas.
- La pila y los datos de AWS pueden generar cargos. Verifica los recursos activos en CloudFormation y RDS, no solo el código local.
- Las operaciones de administración SQS pueden crear/eliminar colas. Restringe su acceso a administradores antes de permitir cuentas públicas.
