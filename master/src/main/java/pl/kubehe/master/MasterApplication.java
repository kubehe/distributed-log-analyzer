package pl.kubehe.master;

import com.rabbitmq.client.Connection;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

@Slf4j
@RestController
@SpringBootApplication
public class MasterApplication {
  private static final String QUEUE = "distributed.log.analyzer";

  public static void main(String[] args) {
    SpringApplication.run(MasterApplication.class, args);
  }

  @Bean
  Queue queue() {
    return new Queue(QUEUE);
  }

  @Bean
  Mono<Connection> connectionMono(ConnectionFactory connectionFactory) {
    return Mono.fromCallable(() -> connectionFactory.createConnection().getDelegate()).cache();
  }

  @Bean
  Sender sender(Mono<Connection> connectionMono) {
    return RabbitFlux.createSender(new SenderOptions().connectionMono(connectionMono));
  }


  @Autowired
  private Sender sender;

  @PostMapping(value = "/upload")
  Mono<Void> upload(@RequestPart("file") Mono<FilePart> file) {
    return sender.send(
      file.flatMapMany(filePart -> {
        log.info("processing file: " + filePart.filename());
        return filePart.content().map(dataBuffer ->
          Try.of(() -> dataBuffer.asInputStream().readAllBytes()).getOrNull()
        );
      }).map(bytes -> new OutboundMessage("", QUEUE, bytes)))
      .doOnSuccess(v -> log.info("finished processing file")).doOnError(throwable -> log.error(throwable.getMessage()));
  }


}
