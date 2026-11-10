package cl.duoc.cloudnative.admin.api;

import cl.duoc.cloudnative.admin.service.QueueAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/queues")
public class QueueAdminController {
    private final QueueAdminService adminService;

    public QueueAdminController(QueueAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<String> listQueues() {
        return adminService.listQueues();
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createQueue(@Valid @RequestBody QueueRequest request) {
        String queueUrl = adminService.createQueue(request);
        return ResponseEntity.created(URI.create(queueUrl))
                .body(Map.of("message", "Cola SQS creada", "name", request.name(), "url", queueUrl));
    }

    @DeleteMapping("/{queueName}")
    public ResponseEntity<Void> deleteQueue(@PathVariable String queueName) {
        adminService.deleteQueue(queueName);
        return ResponseEntity.noContent().build();
    }
}
