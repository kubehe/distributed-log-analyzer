package pl.kubehe.master;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import io.vavr.control.Try;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

@Slf4j
@RestController
@SpringBootApplication
public class MasterApplication {

  public static void main(String[] args) {
    SpringApplication.run(MasterApplication.class, args);
  }
}

@Configuration
class QueueConfiguration {

  static final String JOB_QUEUE = "distributed.log.analyzer.job";
  static final String RESULT_QUEUE = "distributed.log.analyzer.result";

  @Bean
  Queue jobQueue() {
    return new Queue(JOB_QUEUE);
  }

  @Bean
  Queue resultQueue() {
    return new Queue(RESULT_QUEUE);
  }

  @Bean
  Mono<Connection> connectionMono(ConnectionFactory connectionFactory) {
    return Mono.fromCallable(() -> connectionFactory.createConnection().getDelegate()).cache();
  }

  @Bean
  Sender sender(Mono<Connection> connectionMono) {
    return RabbitFlux.createSender(new SenderOptions().connectionMono(connectionMono));
  }

  @Bean
  Receiver receiver(Mono<Connection> connectionMono) {
    return RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connectionMono));
  }
  @Bean
  Flux<Delivery> deliveryFluxBean(Receiver receiver) {
    return receiver.consumeNoAck(RESULT_QUEUE);
  }
}

@AllArgsConstructor
@Slf4j
@RestController
class ApiController {

  private Sender sender;

  private Flux<Delivery> deliveryFlux;

  @PostMapping(value = "/upload")
  public Mono<Void> upload(@RequestPart("file") Mono<FilePart> file) {
    return sender.send(
      file.flatMapMany(filePart -> {
        log.info("processing file: " + filePart.filename());
        return filePart.content().map(dataBuffer ->
          Try.of(() -> dataBuffer.asInputStream().readAllBytes()).getOrElse("{{ERROR}}".getBytes())
        );
      }).map(bytes -> new OutboundMessage("", QueueConfiguration.JOB_QUEUE, bytes)))
      .doOnSuccess(v -> log.info("finished processing file")).doOnError(throwable -> log.error(throwable.getMessage()));
  }

  @GetMapping(value = "/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> getResult() {
    return deliveryFlux.map(delivery -> new String(delivery.getBody()));
  }

}
