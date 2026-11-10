package cl.duoc.cloudnative.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record QueueRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") @Size(max = 80) String name
) {
}
