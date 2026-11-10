package cl.duoc.cloudnative.admin.aws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QueueAdminLambdaHandlerTest {
    @Test
    void acceptsQueueAdministratorGroupClaim() {
        assertTrue(QueueAdminLambdaHandler.isQueueAdministrator(
                Map.of("cognito:groups", "[\"QueueAdministrators\"]")));
    }

    @Test
    void rejectsClaimsWithoutQueueAdministratorGroup() {
        assertFalse(QueueAdminLambdaHandler.isQueueAdministrator(Map.of()));
        assertFalse(QueueAdminLambdaHandler.isQueueAdministrator(
                Map.of("cognito:groups", "[\"Customers\"]")));
        assertFalse(QueueAdminLambdaHandler.isQueueAdministrator(
                Map.of("cognito:groups", "not-json")));
    }
}
