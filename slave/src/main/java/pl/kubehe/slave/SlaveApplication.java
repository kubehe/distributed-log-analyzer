package pl.kubehe.slave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import io.vavr.Function1;
import io.vavr.control.Try;
import lombok.*;
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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;


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
  static final String PROCESSING_QUEUE = "distributed.log.analyzer.processing";
  static final String CALCULATION_QUEUE = "distributed.log.analyzer.calculation";
  static final String AGGREGATION_QUEUE = "distributed.log.analyzer.aggregation";

  @Bean
  Queue jobQueue() {
    return new Queue(PROCESSING_QUEUE);
  }

  @Bean
  Queue calculationQueue() {
    return new Queue(CALCULATION_QUEUE);
  }

  @Bean
  Queue aggregationQueue() {
    return new Queue(CALCULATION_QUEUE);
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
//  @Bean
//  Flux<AcknowledgableDelivery> deliveryFluxBean(Receiver receiver) {
//    return receiver.consumeManualAck(PROCESSING_QUEUE);
//  }
}

@Component
@AllArgsConstructor
@Slf4j
class CalculationService {

  private final String regex = "^(\\S+) (\\S+) (\\S+) \\[([\\w:/]+\\s[+\\-]\\d{4})\\] \"(\\S+)\\s?(\\S+)?\\s?(\\S+)?\" (\\d{3}|-) (\\d+|-)\\s?\"?([^\"]*)\"?\\s?\"?([^\"]*)?\"?$";

  private Sender sender;

  private ObjectMapper objectMapper;

  @RabbitListener(queues = QueueConfiguration.PROCESSING_QUEUE)
  public Mono<Void> handleJobs(byte[] message) {

    var pattern = Pattern.compile(regex);


    return sender.send(
      Mono.just(message)
        .map(String::new)
        .map(msg -> msg.split("\n"))
        .flatMapMany(Flux::fromArray)
        .map(pattern::matcher)
        .filter(Matcher::find)
        .map(LogsMapper::logsMapper)
        .collectList()
        .map(msg -> Try.of(() -> objectMapper.writeValueAsBytes(msg)).get())
        .map(msg -> new OutboundMessage("", QueueConfiguration.CALCULATION_QUEUE, msg)));
  }

}

@Component
@AllArgsConstructor
class AggregationService {

  private Sender sender;

  private ObjectMapper objectMapper;

  @RabbitListener(queues = QueueConfiguration.CALCULATION_QUEUE)
  public Mono<Void> handleJobs(byte[] message) {

    return sender.send(
      Mono.just(message)
        .map(msg -> Try.of(() -> (List<Logs>) objectMapper.readValue(msg, new TypeReference<List<Logs>>() {
        })).get())
        .map(LogsMapper::mapLogs)
        .map(msg -> Try.of(() -> objectMapper.writeValueAsBytes(msg)).get())
        .map(msg -> new OutboundMessage("", QueueConfiguration.AGGREGATION_QUEUE, msg)));
  }

}

class LogsMapper {

  static Logs logsMapper(Matcher matcher) {
    return Logs.builder()
      .ip(matcher.group(1))
      .date(matcher.group(4))
      .method(matcher.group(5))
      .endpoint(matcher.group(6))
      .protocol(matcher.group(7))
      .status(matcher.group(8))
      .port(matcher.group(9))
      .origin(matcher.group(10))
      .userAgent(matcher.group(11))
      .build();
  }

  static AggregatedLogs mapLogs(List<Logs> logsList) {

    Function1<Function1<Logs, String>, Map<String, Long>> countByField = (mapper) -> {
      var groupedBy = logsList.stream().collect(groupingBy(mapper));
      return groupedBy.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey(), Long.valueOf(entry.getValue().size())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    };

    return AggregatedLogs.builder()
      .ip(countByField.apply(Logs::getIp))
      .date(countByField.apply(Logs::getDate))
      .method(countByField.apply(Logs::getMethod))
      .endpoint(countByField.apply(Logs::getEndpoint))
      .protocol(countByField.apply(Logs::getProtocol))
      .status(countByField.apply(Logs::getStatus))
      .port(countByField.apply(Logs::getPort))
      .origin(countByField.apply(Logs::getOrigin))
      .userAgent(countByField.apply(Logs::getUserAgent))
      .build();
  }

}

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
class AggregatedLogs {
  private Map<String, Long> ip;
  private Map<String, Long> date;
  private Map<String, Long> method;
  private Map<String, Long> endpoint;
  private Map<String, Long> protocol;
  private Map<String, Long> status;
  private Map<String, Long> port;
  private Map<String, Long> origin;
  private Map<String, Long> userAgent;
}


@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
class Logs {
  private String ip;
  private String date;
  private String method;
  private String endpoint;
  private String protocol;
  private String status;
  private String port;
  private String origin;
  private String userAgent;
}