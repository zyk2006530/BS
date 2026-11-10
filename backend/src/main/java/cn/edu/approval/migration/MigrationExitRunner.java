package cn.edu.approval.migration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot migration mode used by the deployment {@code migrate} service.
 *
 * <p>Flyway runs during context startup, before this runner. The context is then closed and the JVM
 * exits, so the service completes before the backend is allowed to start. A migration failure
 * prevents startup and therefore produces a non-zero exit.
 */
@Component
@Profile("migrate")
@Order(2)
public class MigrationExitRunner implements ApplicationRunner {
  private final ApplicationContext context;

  public MigrationExitRunner(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
