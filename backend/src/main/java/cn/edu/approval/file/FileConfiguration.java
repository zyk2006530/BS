package cn.edu.approval.file;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

@Configuration
@EnableScheduling
@Profile("db & !migrate")
public class FileConfiguration {
  public static int activeNetworkOperations() {
    return DeadlineInputStream.activeNetworkOperations();
  }

  /** Container I/O must not preempt the authenticated 120-second application deadline. */
  @Bean
  public org.springframework.boot.web.server.WebServerFactoryCustomizer<
          org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory>
      uploadConnectorDeadline() {
    return factory ->
        factory.addConnectorCustomizers(
            connector -> {
              if (connector.getProtocolHandler()
                  instanceof org.apache.coyote.http11.AbstractHttp11Protocol<?> protocol) {
                protocol.setDisableUploadTimeout(false);
                protocol.setConnectionUploadTimeout(125000);
              }
            });
  }

  /** Prevent container multipart parsing before authentication/deadline and lease creation. */
  @Bean(name = "multipartResolver")
  public MultipartResolver multipartResolver() {
    return new MultipartResolver() {
      @Override
      public boolean isMultipart(HttpServletRequest request) {
        return false;
      }

      @Override
      public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
        throw new UnsupportedOperationException("Streaming file endpoint only");
      }

      @Override
      public void cleanupMultipart(MultipartHttpServletRequest request) {
        /* UploadBody owns its stream. */
      }
    };
  }
}
