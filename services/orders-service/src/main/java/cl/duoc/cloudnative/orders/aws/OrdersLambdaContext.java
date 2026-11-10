package cl.duoc.cloudnative.orders.aws;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import cl.duoc.cloudnative.orders.OrdersApplication;

final class OrdersLambdaContext {
    private static final ConfigurableApplicationContext CONTEXT =
            new SpringApplicationBuilder(OrdersApplication.class)
                    .profiles("aws", "lambda")
                    .web(WebApplicationType.NONE)
                    .run();

    private OrdersLambdaContext() {
    }

    static <T> T getBean(Class<T> type) {
        return CONTEXT.getBean(type);
    }
}
