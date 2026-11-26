package org.lab.gpuqueue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

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
        Path dataDirectory = Path.of(args[1]);
        switch (mode) {
            case "migrate" -> Flyway.configure()
                    .dataSource(jdbcUrlFor(dataDirectory), "sa", "")
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
            case "init-admin" -> {
                try (var context = startMode(mode, dataDirectory)) {
                    context.getBean(InitialAdministrator.class).initialize();
                }
            }
            case "serve" -> startMode(mode, dataDirectory);
            default -> throw new IllegalArgumentException("模式须为 migrate、init-admin 或 serve。");
        }
    }

    public static ConfigurableApplicationContext startMode(String mode, Path dataDirectory, String... extraArgs) {
        String jdbcUrl = jdbcUrlFor(dataDirectory);
        return switch (mode) {
            case "init-admin" -> startApplication(jdbcUrl, WebApplicationType.NONE, false, extraArgs);
            case "serve" -> startApplication(jdbcUrl, WebApplicationType.SERVLET, true, extraArgs);
            default -> throw new IllegalArgumentException("模式须为 migrate、init-admin 或 serve。");
        };
    }

    private static String jdbcUrlFor(Path dataDirectory) {
        Path database = dataDirectory.toAbsolutePath().normalize().resolve("gpu-queue");
        return "jdbc:h2:file:" + database.toString().replace('\\', '/');
    }

    private static ConfigurableApplicationContext startApplication(
            String jdbcUrl, WebApplicationType webType, boolean scannerEnabled, String... extraArgs) {
        var runArgs = new ArrayList<String>(3 + extraArgs.length);
        runArgs.add("--queue.jdbc-url=" + jdbcUrl + ";IFEXISTS=TRUE");
        runArgs.add("--spring.main.web-application-type=" + webType.name());
        runArgs.add("--queue.scanner.enabled=" + scannerEnabled);
        Collections.addAll(runArgs, extraArgs);
        return new SpringApplicationBuilder(GpuQueueApplication.class)
                .web(webType)
                .run(runArgs.toArray(String[]::new));
    }
}
