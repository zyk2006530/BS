package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.MultipartConfigElement;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

class FileConfigurationTest {
  @Test
  void connectorTimeoutAllowsApplicationDeadlineToWin() {
    var factory = mock(TomcatServletWebServerFactory.class);
    new FileConfiguration().uploadConnectorDeadline().customize(factory);
    var customizer = ArgumentCaptor.forClass(TomcatConnectorCustomizer.class);
    verify(factory).addConnectorCustomizers(customizer.capture());
    var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
    customizer.getValue().customize(connector);
    var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
    assertThat(protocol.getDisableUploadTimeout()).isFalse();
    assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(125000);
    // Do not weaken the normal keep-alive/connection timeout or security authorization.
  }

  @Test
  void disabledBootMultipartLeavesOnlyAuthenticatedStreamingResolver() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
        .withUserConfiguration(FileConfiguration.class)
        .withPropertyValues("spring.profiles.active=db", "spring.servlet.multipart.enabled=false")
        .run(
            context -> {
              assertThat(context).hasBean("multipartResolver");
              assertThat(context).doesNotHaveBean(MultipartConfigElement.class);
            });
  }

  @Test
  void enabledBootMultipartWouldRegisterContainerParsing() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
        .withPropertyValues("spring.servlet.multipart.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(MultipartConfigElement.class));
  }
}
