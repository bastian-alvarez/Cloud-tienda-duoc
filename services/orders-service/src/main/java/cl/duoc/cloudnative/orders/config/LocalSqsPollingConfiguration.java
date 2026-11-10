package cl.duoc.cloudnative.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("!lambda")
@EnableScheduling
public class LocalSqsPollingConfiguration {
}
