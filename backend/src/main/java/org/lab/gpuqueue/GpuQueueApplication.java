package org.lab.gpuqueue;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.lab.gpuqueue.account.InitialAdministrator;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class GpuQueueApplication {
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：java -jar gpu-queue.jar <migrate|init-admin|serve> <数据目录>");
        }

        String mode = args[0];
        Path database = Path.of(args[1]).toAbsolutePath().normalize().resolve("gpu-queue");
        String jdbcUrl = "jdbc:h2:file:" + database.toString().replace('\\', '/');
        switch (mode) {
            case "migrate" -> Flyway.configure()
                    .dataSource(jdbcUrl, "sa", "")
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
            case "init-admin" -> {
                try (var context = startApplication(jdbcUrl, WebApplicationType.NONE)) {
                    context.getBean(InitialAdministrator.class).initialize();
                }
            }
            case "serve" -> startApplication(jdbcUrl, WebApplicationType.SERVLET);
            default -> throw new IllegalArgumentException("模式须为 migrate、init-admin 或 serve。");
        }
    }

    private static ConfigurableApplicationContext startApplication(String jdbcUrl, WebApplicationType webType) {
        return new SpringApplicationBuilder(GpuQueueApplication.class)
                .web(webType)
                .run("--queue.jdbc-url=" + jdbcUrl + ";IFEXISTS=TRUE",
                        "--spring.main.web-application-type=" + webType.name());
    }
}
