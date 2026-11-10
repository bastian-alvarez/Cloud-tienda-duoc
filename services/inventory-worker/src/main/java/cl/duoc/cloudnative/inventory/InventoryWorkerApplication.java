package cl.duoc.cloudnative.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryWorkerApplication.class, args);
    }
}

