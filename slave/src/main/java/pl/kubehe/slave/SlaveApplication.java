package pl.kubehe.slave;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

@Slf4j
@RestController
@SpringBootApplication
public class SlaveApplication {

  public static void main(String[] args) {
    SpringApplication.run(SlaveApplication.class, args);
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
    return receiver.consumeNoAck(JOB_QUEUE);
  }
}

@Component
@AllArgsConstructor
@Slf4j
class QueueService {

  private Sender sender;

  @RabbitListener(queues = QueueConfiguration.JOB_QUEUE)
  public Mono<Void> handleJobs(byte[] message) {

    return sender.send(Mono.just(message).map(msg -> new OutboundMessage("", QueueConfiguration.RESULT_QUEUE, (new String(msg) + " processed").getBytes())));
  }


}
