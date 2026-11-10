# Flujo del proyecto TiendaDuoc en AWS

## 1. Propósito y estado

Este documento describe cómo se conectan los componentes AWS de TiendaDuoc y qué responsabilidad cumple cada tecnología. La arquitectura evita instancias EC2, usa Amazon SQS para el procesamiento asíncrono y Amazon SNS para distribuir correos de confirmación de prueba.

Al **6 de noviembre de 2026**, la última comprobación encontró la pila `tiendaduoc-sqs-aurora` en estado `UPDATE_COMPLETE` en `us-east-1`, Aurora disponible en clase `db.t3.medium` y no pública, y el último deployment de Amplify en `SUCCEED`. También se verificó la suscripción de correo SNS y se probó el recorrido SQS → Lambda → SNS. El estado de AWS puede cambiar después de esta comprobación.

**Frontend Amplify:** [https://main.d68hvgua8rzhh.amplifyapp.com](https://main.d68hvgua8rzhh.amplifyapp.com)  
**API Gateway HTTP API:** [https://s8vtyx4ibc.execute-api.us-east-1.amazonaws.com](https://s8vtyx4ibc.execute-api.us-east-1.amazonaws.com)

Aurora es privada; no se debe exponer ni compartir la contraseña de base de datos. CloudFormation es la fuente de verdad para el estado actual de la pila.

## 2. Vista general

```text
Navegador Angular
   ├── inicio de sesión OIDC ───────────────> Amazon Cognito
   └── HTTPS + JWT ─────────────────────────> API Gateway HTTP API
                                                ├── pedidos ─> Lambda orders-api
                                                │               ├── Aurora PostgreSQL (orders)
                                                │               └── Amazon SQS
                                                └── administración ─> Lambda queue-admin

Amazon SQS
   ├── payment ─────────────> Lambda payment-worker ──> payment-results ─┐
   ├── inventory ───────────> Lambda inventory-worker ─> inventory-results ├─> Lambdas de resultados
   └── notifications ───────> Lambda notification-worker ──> SNS ──> correo suscrito

S3 almacena los JAR de Lambda. CloudFormation administra los recursos.
Amplify aloja el frontend. Las colas de trabajo y de resultados tienen DLQ asociadas.
```

Los workers de pago e inventario son simulaciones académicas. El worker de notificación publica un correo demostrativo en un tema SNS; solo lo reciben suscriptores que hayan confirmado su dirección.

## 3. Flujo completo de un pedido

1. **Frontend alojado:** AWS Amplify Hosting publica el build estático de Angular en una URL HTTPS. El usuario abre esa dirección directamente; el computador local no necesita ejecutar `ng serve`.
2. **Autenticación:** la persona inicia sesión desde Angular con Cognito. El cliente web utiliza OIDC Authorization Code con PKCE; no contiene un secreto de cliente.
3. **Solicitud:** el frontend envía `POST /api/orders` por HTTPS con el access token en `Authorization: Bearer ...`.
4. **Autorización:** API Gateway verifica el JWT de Cognito antes de invocar la función. Una llamada sin token válido responde `401` o `403` y no llega al código del pedido.
5. **Creación del pedido:** la Lambda `orders-api` valida el contenido y guarda el pedido con estado inicial `PENDIENTE` en Aurora, dentro del esquema `orders`.
6. **Publicación:** una vez confirmado el commit de Aurora, el servicio publica el evento del pedido en tres colas independientes: pago, inventario y notificaciones. Así cada proceso puede avanzar de manera desacoplada.
7. **Pago e inventario:** las colas payment e inventory activan sus Lambdas respectivas. Ambos workers registran el evento procesado para evitar repetir el mismo trabajo y publican un resultado en su cola de resultados.
8. **Actualización:** las Lambdas de resultados guardan el resultado en el pedido de Aurora. Un pago aprobado más una reserva de inventario actualiza el estado visible a `COMPLETADO`.
9. **Notificación:** la cola notifications activa el worker, que publica en un tema SNS una confirmación de prueba con los datos del pedido (identificador, correo ingresado, SKU, cantidad y total). SNS entrega el correo a sus suscriptores confirmados. En esta demo el destinatario es fijo, configurado con `NotificationEmail`: el correo ingresado en la compra aparece en el contenido, pero no determina el destinatario. Por eso la bandeja suscrita recibe las confirmaciones de todos los pedidos.
10. **Errores y reintentos:** si un mensaje falla repetidamente, Amazon SQS lo traslada a la dead-letter queue (DLQ) asociada para poder inspeccionarlo y recuperarlo.

El envío después del commit evita que un worker lea un pedido aún no confirmado. Sin embargo, la escritura en Aurora y la publicación a SQS no son una única transacción; para producción se recomienda un patrón **Transactional Outbox**.

## 4. Funciones Lambda desplegadas

La pila contiene siete funciones Lambda Java 21. Los artefactos `.jar` se construyen desde los módulos del proyecto y se cargan en S3; Lambda ejecuta el handler configurado, no ejecuta el JAR desde S3 como si S3 fuera un servidor.

| Función | Activación y responsabilidad |
|---|---|
| `orders-api` | Integración HTTP de API Gateway. Crea, lista y consulta pedidos; conecta con Aurora y publica los eventos del pedido a SQS. |
| `payment-worker` | Se activa desde la cola payment; simula la aprobación/rechazo y envía el resultado a payment-results. Usa el esquema `payment` para idempotencia. |
| `inventory-worker` | Se activa desde inventory; simula reservar inventario y envía el resultado a inventory-results. Usa el esquema `inventory` para idempotencia. |
| `notification-worker` | Se activa desde notifications y publica el resumen del pedido en el tema SNS; la dirección suscrita debe confirmar la suscripción por correo. |
| `payment-results` | Se activa desde payment-results y actualiza el resultado del pago en el pedido de Aurora. |
| `inventory-results` | Se activa desde inventory-results y registra la reserva de inventario en el pedido de Aurora. |
| `queue-admin` | Atiende las rutas administrativas para listar, crear y eliminar colas SQS. Requiere pertenecer al grupo Cognito `QueueAdministrators`. |

Las funciones que acceden a Aurora se conectan a través de la VPC y su grupo de seguridad; Aurora no acepta conexiones públicas. Los event source mappings conectan las colas SQS con las funciones worker.

## 5. Servicios y tecnologías de AWS

| Tecnología AWS | Para qué sirve en TiendaDuoc | Detalle de esta implementación |
|---|---|---|
| **AWS Amplify Hosting** | Publica el frontend web estático por HTTPS. | El script compila Angular con la configuración AWS y realiza una publicación manual en la rama `main`. Amplify entrega una URL HTTPS administrada; no requiere un servidor local en ejecución. |
| **Amazon S3** | Almacenamiento de artefactos de despliegue. | Conserva los cinco JAR AWS que CloudFormation asigna a las funciones Lambda. El bucket es privado, bloquea acceso público y usa cifrado del lado del servidor. S3 almacena los paquetes; no ejecuta los servicios. |
| **AWS Lambda** | Ejecución bajo demanda de código sin administrar servidores. | Ejecuta los handlers HTTP, workers y procesadores de resultados Java 21. Los event source mappings hacen que los mensajes de SQS activen workers. No se necesita un servidor EC2 permanente. |
| **Amazon API Gateway HTTP API** | Punto de entrada HTTPS para el backend. | Enruta las solicitudes a Lambda, aplica CORS para el frontend y usa un authorizer JWT de Cognito. La API es HTTP API (API Gateway v2), no Amazon API Management ni Azure API Management. |
| **Amazon Cognito User Pools** | Identidad de usuarios de la aplicación. | Registra usuarios y emite tokens OIDC/JWT para el frontend. El cliente web usa flujo Authorization Code con PKCE. Cognito autentica usuarios finales; no reemplaza IAM ni las credenciales del Learner Lab. |
| **Cognito User Pool Group** | Agrupación para autorización de operaciones privilegiadas. | El grupo `QueueAdministrators` limita la administración de colas. El handler verifica el claim `cognito:groups`; los usuarios registrados no se vuelven administradores automáticamente. |
| **Amazon Aurora PostgreSQL** | Persistencia relacional administrada. | Aurora PostgreSQL 17.9 aloja la base `tiendaduoc` con instancia writer `db.t3.medium`. Está cifrada y no es públicamente accesible. |
| **Hibernate/JPA (en Lambda)** | Mapeo entre entidades Java y tablas PostgreSQL. | `ddl-auto: update` y `create_namespaces: true` permiten crear/actualizar estructuras básicas al iniciar el contexto Spring. Los servicios usan esquemas separados: `orders`, `payment` e `inventory`. No equivale a migraciones versionadas para producción. |
| **Amazon SQS** | Colas administradas para desacoplar y distribuir trabajo. | Mantiene colas separadas de pago, inventario, notificaciones y resultados. El productor envía copias del evento a las colas necesarias; cada consumidor escala de manera independiente. Las colas usan cifrado administrado por SQS. |
| **Amazon SNS** | Distribución de confirmaciones de pedido por correo. | El worker publica el resumen del pedido en el tema `tiendaduoc-sqs-aurora-order-notifications`. La suscripción de correo fija se configura con el parámetro CloudFormation `NotificationEmail` y debe confirmarse. El campo de correo del pedido solo aparece en el mensaje: SNS no lo usa para enrutar al cliente. No enviar información sensible ni usar como comprobante comercial. |
| **SQS Dead-Letter Queues (DLQ)** | Aislamiento de mensajes que no pueden procesarse. | Cada cola de trabajo y de resultados tiene una DLQ. La política redrive configurada mueve un mensaje después de tres recepciones fallidas para que no bloquee indefinidamente el flujo normal. |
| **Amazon VPC y subnets** | Red privada donde se conectan los componentes que necesitan acceder a Aurora. | Aurora y las Lambdas con acceso a datos se conectan por subnets de la VPC. La base no se expone directamente a Internet. |
| **Security Groups** | Firewall con reglas asociadas a recursos de red. | Aurora permite PostgreSQL (puerto 5432) desde el grupo de las Lambdas. El endpoint de SQS permite HTTPS (443) desde las Lambdas. |
| **VPC Interface Endpoint para SQS** | Acceso privado desde la VPC al servicio regional SQS. | Evita que las Lambdas que acceden a Aurora dependan de una IP pública/NAT para comunicarse con SQS. |
| **AWS Secrets Manager** | Custodia de secretos de aplicación. | CloudFormation genera la contraseña de Aurora y la guarda en un secreto. La plantilla la referencia sin incluirla en outputs ni en el código fuente. |
| **AWS IAM y `LabRole`** | Control de permisos de AWS para las funciones. | Las Lambdas usan el rol existente `LabRole` del entorno AWS Academy; la pila no crea un rol nuevo. Las acciones disponibles dependen de la política del Learner Lab. No compartas credenciales temporales de AWS ni tokens Cognito. |
| **AWS CloudFormation** | Infraestructura como código y ciclo de vida de la pila. | Crea y administra API, Cognito, Amplify, Lambdas, colas, tema/política/suscripción SNS, Aurora, secreto, grupos de seguridad y endpoint. La pila se llama `tiendaduoc-sqs-aurora`; el bucket S3 de artefactos lo prepara el script de despliegue. |
| **Amazon CloudWatch Logs** | Observabilidad de funciones Lambda. | Lambda escribe logs de inicialización, procesamiento y errores en grupos `/aws/lambda/<nombre-de-función>`. Sirven para diagnosticar fallos de handlers y consumos de SQS. |
| **AWS STS** | Verificación de identidad de la sesión AWS activa. | El script de despliegue consulta la identidad de la sesión temporal con `GetCallerIdentity` antes de actuar. STS no autentica a los usuarios de la tienda. |

### Servicios que no forman parte del despliegue AWS

- **Amazon EC2:** no se creó ninguna instancia. Que CloudFormation use tipos de recurso `AWS::EC2::*` para security groups, subnets o VPC endpoints no significa que exista una máquina virtual.
- **Amazon MQ/RabbitMQ:** no se desplegó broker RabbitMQ en AWS. La mensajería cloud es SQS. RabbitMQ de la rúbrica no está cubierto literalmente por esta arquitectura.
- **Amazon API Management:** no es un servicio AWS; el API manager de esta aplicación es Amazon API Gateway.
- **CloudFront directo:** no se usa porque Learner Lab deniega sus permisos; Amplify Hosting sirve la aplicación con HTTPS administrado.

## 6. Endpoints HTTP

Todas estas rutas pasan por el JWT authorizer de API Gateway:

| Método | Ruta | Uso |
|---|---|---|
| `POST` | `/api/orders` | Crear un pedido y publicar eventos de trabajo. |
| `GET` | `/api/orders` | Listar pedidos. |
| `GET` | `/api/orders/{id}` | Consultar un pedido por identificador. |
| `GET` | `/api/admin/queues` | Listar colas SQS (requiere grupo `QueueAdministrators`). |
| `POST` | `/api/admin/queues` | Crear una cola SQS (requiere grupo `QueueAdministrators`). |
| `DELETE` | `/api/admin/queues/{queueName}` | Eliminar una cola SQS (requiere grupo `QueueAdministrators`). |

## 7. Seguridad, costos y límites

- **Aurora tiene costo continuo** mientras la instancia esté activa. También pueden generar cargos las funciones, API Gateway, SQS, el endpoint VPC, almacenamiento y transferencia. Revisa los costos en AWS Academy.
- El script `infra/cleanup-aurora-sqs.sh` elimina la pila y Aurora; **la eliminación borra permanentemente la base y sus datos**. Guárdalos antes de limpiar.
- La cuenta AWS puede contener recursos o artefactos anteriores independientes de esta pila. El despliegue nuevo no implica que esos recursos hayan sido migrados o eliminados.
- El flujo de pedido no es una transacción atómica entre PostgreSQL y SQS. El patrón Outbox, migraciones de esquema, políticas IAM de mínimo privilegio y límites de datos por usuario son mejoras previas a producción.
- SQS ofrece colas, reintentos y DLQ, pero no ofrece exchanges, routing keys, protocolo AMQP ni consola RabbitMQ. Si la evaluación pide explícitamente RabbitMQ/Amazon MQ, informa esta diferencia.

## 8. Referencias del repositorio

- Infraestructura: [`infra/aurora-sqs-stack.yaml`](infra/aurora-sqs-stack.yaml)
- Despliegue: [`infra/deploy-aurora-sqs.sh`](infra/deploy-aurora-sqs.sh)
- Limpieza: [`infra/cleanup-aurora-sqs.sh`](infra/cleanup-aurora-sqs.sh)
- Configuración del frontend AWS: el script genera `frontend/src/app/environment.aws.ts` durante la publicación; el build se aloja en Amplify y no depende del equipo local una vez publicado.
- Instrucciones de ejecución: [`README.md`](README.md)

Para volver a desplegar, el script requiere `NOTIFICATION_EMAIL` con la dirección fija que recibirá las confirmaciones; la persona dueña de esa bandeja debe confirmar la suscripción que envía SNS. El script compila y verifica los módulos Java, sube cinco JAR al bucket S3 privado, actualiza CloudFormation y las Lambdas, compila Angular y publica manualmente el frontend en Amplify. No se configuró CI/CD automático desde un repositorio.
