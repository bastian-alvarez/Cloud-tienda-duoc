package cl.duoc.cloudnative.admin.aws;

import cl.duoc.cloudnative.admin.api.QueueRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

public class QueueAdminLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SqsClient SQS_CLIENT = createSqsClient();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String method = event.getRequestContext().getHttp().getMethod();
        String path = event.getRawPath();
        var authorizer = event.getRequestContext().getAuthorizer();
        var jwt = authorizer == null ? null : authorizer.getJwt();
        Map<String, String> claims = jwt == null || jwt.getClaims() == null
                ? Map.of()
                : jwt.getClaims();
        if (!isQueueAdministrator(claims)) {
            return response(403, Map.of("title", "Forbidden", "detail", "Se requiere el grupo QueueAdministrators."));
        }
        try {
            if ("GET".equalsIgnoreCase(method) && "/api/admin/queues".equals(path)) {
                return response(200, SQS_CLIENT.listQueues(ListQueuesRequest.builder().build()).queueUrls());
            }
            if ("POST".equalsIgnoreCase(method) && "/api/admin/queues".equals(path)) {
                String body = event.getBody();
                if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
                    body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
                }
                QueueRequest request = OBJECT_MAPPER.readValue(body, QueueRequest.class);
                if (request.name() == null || !request.name().matches("[A-Za-z0-9_-]{1,80}")) {
                    return response(400, Map.of("title", "Bad Request", "detail", "Nombre de cola inválido."));
                }
                String queueUrl = SQS_CLIENT.createQueue(CreateQueueRequest.builder()
                        .queueName(request.name())
                        .attributes(Map.of(QueueAttributeName.SQS_MANAGED_SSE_ENABLED, "true"))
                        .build()).queueUrl();
                return response(201, Map.of("name", request.name(), "url", queueUrl));
            }
            if ("DELETE".equalsIgnoreCase(method) && path != null
                    && path.matches("/api/admin/queues/[A-Za-z0-9_-]{1,80}")) {
                String queueName = path.substring("/api/admin/queues/".length());
                String queueUrl = SQS_CLIENT.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(queueName)
                        .build()).queueUrl();
                SQS_CLIENT.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                return response(204, Map.of());
            }
            return response(404, Map.of("title", "Not Found"));
        } catch (Exception exception) {
            System.err.println("Queue admin failed: " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
            return response(500, Map.of("title", "Internal Server Error"));
        }
    }

    static boolean isQueueAdministrator(Map<String, String> claims) {
        String groupsClaim = claims.get("cognito:groups");
        if (groupsClaim == null) {
            return false;
        }
        if ("QueueAdministrators".equals(groupsClaim)) {
            return true;
        }
        try {
            JsonNode groups = OBJECT_MAPPER.readTree(groupsClaim);
            if (!groups.isArray()) {
                return false;
            }
            for (JsonNode group : groups) {
                if (group.isTextual() && "QueueAdministrators".equals(group.asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static SqsClient createSqsClient() {
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        String endpoint = System.getenv("SQS_ENDPOINT_URL");
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static APIGatewayV2HTTPResponse response(int status, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(OBJECT_MAPPER.writeValueAsString(body))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo serializar respuesta de administración SQS", exception);
        }
    }
}
