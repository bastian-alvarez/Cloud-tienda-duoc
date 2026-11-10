# Implementación y arquitectura de TiendaDuoc

## Estado y alcance

El repositorio contiene dos rutas de ejecución:

1. **Local:** Angular, cinco aplicaciones Spring Boot, PostgreSQL, LocalStack SQS y Keycloak.
2. **AWS:** AWS Amplify Hosting sirve el frontend Angular por HTTPS; S3 guarda los paquetes Java; Cognito y API Gateway HTTP API controlan la entrada; Lambdas Java 21 procesan solicitudes y mensajes; Aurora PostgreSQL persiste datos y SQS con DLQ desacopla el trabajo.

`infra/aurora-sqs-stack.yaml` define la ruta AWS sin EC2. En esta sesión `tiendaduoc-sqs-aurora` se desplegó en `us-east-1` con estado `CREATE_COMPLETE`; se probó la autenticación obligatoria en API Gateway y el flujo de pedidos hasta `COMPLETADO`. Confirma la consola/CLI para conocer el estado actual. El stack y los artefactos previos de AWS no se consideran migrados ni eliminados.

La decisión de no usar EC2 y el bloqueo de Amazon MQ llevan a sustituir RabbitMQ por SQS. Esto permite procesamiento asíncrono y DLQ, pero no conserva exchanges, routing keys, protocolo AMQP ni consola Rabbit. Si la rúbrica exige RabbitMQ literalmente, la arquitectura no satisface ese indicador.

## Componentes AWS

| Recurso | Función |
|---|---|
| AWS Amplify Hosting | Publica el build de Angular en una URL HTTPS administrada; el deployment del frontend es manual desde el script del proyecto. |
| Amazon S3 | Bucket privado y cifrado para los cinco JAR desplegados por CloudFormation. |
| Amazon API Gateway HTTP API | Expone las rutas de pedidos y administración SQS. Integra handlers Lambda y valida JWT de Cognito. |
| Amazon Cognito User Pool | Registro/inicio de sesión web con OIDC Authorization Code y PKCE. |
| AWS Lambda Java 21 | Handler HTTP de pedidos, workers de pago/inventario/notificación, procesadores de resultados y administración SQS. El worker de notificaciones publica confirmaciones demostrativas en SNS. |
| Amazon Aurora PostgreSQL 17.9 | Base provisionada `db.t3.medium`, privada, con base `tiendaduoc`. |
| Hibernate | Cada servicio usa `ddl-auto: update`; `orders`, `payment` e `inventory` se crean/actualizan en la conexión del servicio correspondiente. |
| Amazon SQS | Colas independientes para pago, inventario, notificaciones y sus resultados. Cada cola de trabajo tiene DLQ y reenvío tras tres recepciones fallidas. |
| Amazon SNS | Envía el resumen de cada pedido a los suscriptores confirmados del tema. La demo configura un correo fijo; no entrega directamente al correo que se escribe en cada pedido. |
| VPC, grupos de seguridad y endpoint SQS | Lambdas acceden a Aurora por PostgreSQL y a SQS por un endpoint privado; no se crea EC2. |
| AWS CloudFormation | Administra y elimina los recursos de la nueva pila como unidad. |
| IAM `LabRole` | Rol de ejecución preexistente usado por las Lambdas; la política del Learner Lab debe permitir sus operaciones. |

La base de datos se crea con contraseña generada en Secrets Manager. El código de la plantilla no imprime el secreto en los outputs. No se debe compartir la contraseña, JWT ni credenciales temporales de Learner Lab.

## Flujo de pedidos AWS

1. Angular inicia sesión en Cognito mediante OIDC/PKCE y obtiene tokens de usuario.
2. API Gateway valida el JWT antes de reenviar las rutas de pedidos a `OrdersApiLambdaHandler`.
3. La Lambda valida el JSON y crea el pedido en el esquema `orders` de Aurora.
4. Después del commit, el publicador envía eventos a las colas de pago, inventario y notificación. La API devuelve el pedido creado.
5. Lambda consume las colas de pago e inventario; cada worker registra idempotencia en su esquema y envía el resultado a una cola de resultados.
6. Las Lambdas de resultados actualizan el estado del pedido en `orders`. Los fallos reiterados se mueven a la DLQ correspondiente.
7. El worker de notificación publica el resumen del pedido en SNS. El correo suscrito debe confirmar la suscripción antes de recibir mensajes. El destinatario es fijo para esta demo académica.

Los pagos y las reservas de inventario son simulaciones académicas. Los SKU de prueba con prefijo `falla` permiten verificar la ruta de error y las DLQ.

El envío después del commit evita que un worker procese un resultado antes de que el pedido sea visible en Aurora. No hace atómica la escritura de base de datos y el envío a SQS; producción requiere un transactional outbox.

La entidad de pedido usa actualizaciones dinámicas de Hibernate para que los resultados concurrentes de pago e inventario no sobrescriban cambios hechos en columnas distintas.

## Endpoints configurados

| Método y ruta | Propósito |
|---|---|
| `POST /api/orders` | Crear un pedido. |
| `GET /api/orders` | Listar pedidos. |
| `GET /api/orders/{id}` | Consultar un pedido. |
| `GET /api/admin/queues` | Listar colas SQS. |
| `POST /api/admin/queues` | Crear una cola SQS. |
| `DELETE /api/admin/queues/{queueName}` | Eliminar una cola SQS. |

Todas las rutas requieren JWT Cognito en API Gateway. Además, el handler administrativo comprueba el claim `cognito:groups` y solo permite operaciones a miembros de `QueueAdministrators`. Agrega manualmente al usuario de prueba al grupo desde Cognito después del despliegue; el registro público no otorga acceso administrativo.

## Esquemas Hibernate

Las configuraciones de `orders-service`, `payment-worker` e `inventory-worker` utilizan PostgreSQL, `spring.jpa.hibernate.ddl-auto: update`, `hibernate.default_schema` y `hibernate.hbm2ddl.create_namespaces: true`. En AWS, CloudFormation proporciona `DB_SCHEMA`, URL, credenciales y `JPA_DDL_AUTO=update`. Esto automatiza la creación/actualización básica de tablas al inicializar las aplicaciones; no sustituye migraciones versionadas para producción.

## Ejecución y despliegue

En local, `compose.yaml` inicia PostgreSQL, LocalStack SQS y Keycloak. Los microservicios se ejecutan localmente y sus perfiles usan las colas de LocalStack.

Para AWS, `infra/deploy-aurora-sqs.sh`:

1. valida la sesión AWS y el rol existente;
2. requiere `NOTIFICATION_EMAIL` para configurar la dirección fija suscrita al tema SNS;
3. compila y prueba los módulos y genera JAR `-aws.jar`;
4. prepara el bucket S3 privado y carga los cinco artefactos;
5. valida la plantilla y despliega `tiendaduoc-sqs-aurora`;
6. actualiza el código de las siete Lambdas desde los objetos S3;
7. genera la configuración Cognito/API y compila Angular;
8. publica el frontend en la rama Amplify `main`, espera el resultado y comprueba la URL HTTPS;
9. muestra los outputs de API, Amplify, Cognito, Aurora y SQS.

La validación de plantilla no despliega recursos. Un despliegue puede fallar por permisos, cuotas, networking, límites del Learner Lab o incompatibilidad del servicio/región. Comprueba el estado de CloudFormation y los eventos del recurso fallido.

## Costos, limpieza y evidencias

Aurora `db.t3.medium`, Amplify Hosting y los endpoints VPC pueden generar cargos. Cuando ya no se necesite la demo y se hayan guardado las evidencias, `infra/cleanup-aurora-sqs.sh` elimina la pila, incluida la aplicación Amplify, Aurora y sus datos, y retira del bucket solo los cinco JAR específicos. El bucket y otros artefactos permanecen.

Para evidencias, verifica en AWS la API, Cognito, las tablas/esquemas de Aurora, SQS/DLQ, invocaciones y logs Lambda. No afirmes que recursos están activos sin comprobación en la cuenta.
