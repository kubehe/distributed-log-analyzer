package pl.kubehe.master;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import io.vavr.Function1;
import io.vavr.Function2;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;

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

  static final String PROCESSING_QUEUE = "distributed.log.analyzer.processing";
  static final String AGGREGATION_QUEUE = "distributed.log.analyzer.aggregation";

  @Bean
  Queue jobQueue() {
    return new Queue(PROCESSING_QUEUE);
  }

  @Bean
  Queue resultQueue() {
    return new Queue(AGGREGATION_QUEUE);
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
    return receiver.consumeNoAck(AGGREGATION_QUEUE);
  }
}

@AllArgsConstructor
@Slf4j
@RestController
class ApiController {

  private Sender sender;

  private ObjectMapper objectMapper;

  private Flux<Delivery> deliveryFlux;

  @PostMapping(value = "/upload")
  public Mono<Void> upload(@RequestPart("file") Mono<FilePart> file) {
    return sender.send(
      file.flatMapMany(filePart -> {
        log.info("processing file: " + filePart.filename());
        return filePart.content().map(dataBuffer ->
          Try.of(() -> dataBuffer.asInputStream().readAllBytes()).getOrElse("".getBytes())
        );
      }).map(bytes -> new OutboundMessage("", QueueConfiguration.PROCESSING_QUEUE, bytes)))
      .doOnSuccess(v -> log.info("finished processing file")).doOnError(throwable -> log.error(throwable.getMessage()));
  }

  @GetMapping(value = "/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<AggregatedLogs> getResult(@RequestParam("simple") Boolean simple) {

    // it's a hugging joke until i discover how to memoize last element in flux so i can combine incoming one with the prev one
    final AggregatedLogs al = AggregatedLogs.builder().build();

    return deliveryFlux.map(delivery -> new String(delivery.getBody()))

      .map(msg -> Try.of(() -> objectMapper.readValue(msg, AggregatedLogs.class)).get())
      .map(msg -> {
        var joinedVal = Mapper.joinAggregatedLogs.apply(msg, al);
        al.setDate(joinedVal.getDate());
        al.setEndpoint(joinedVal.getEndpoint());
        al.setIp(joinedVal.getIp());
        al.setMethod(joinedVal.getMethod());
        al.setOrigin(joinedVal.getOrigin());
        al.setPort(joinedVal.getPort());
        al.setProtocol(joinedVal.getProtocol());
        al.setStatus(joinedVal.getStatus());
        al.setUserAgent(joinedVal.getUserAgent());
        return joinedVal;
      }).map(msg -> {
          log.info("response");
          return simple ?
            AggregatedLogs.builder()
              .method(msg.getMethod())
              .protocol(msg.getProtocol())
              .status(msg.getStatus())
              .build()
            : msg;
        }
      );
  }

}

class Mapper {
  static Function2<AggregatedLogs, AggregatedLogs, AggregatedLogs> joinAggregatedLogs = (a1, a2) -> {
    if (a1 == null && a2 == null) throw new IllegalArgumentException("at least first arg cannot be null!");
    if (a2 == null || a2.getMethod() == null) return a1;
    Function1<Function1<AggregatedLogs, Map<String, Long>>, Map<String, Long>> joinField = (mapper) -> {
      var joinedMap = mapper.apply(a1).entrySet().stream()
        .map(entry -> {
          var value = ofNullable(mapper.apply(a2).get(entry.getKey())).map(size2 -> size2 + entry.getValue()).orElse(entry.getValue());
          return Map.entry(entry.getKey(), value);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      mapper.apply(a2).forEach(joinedMap::putIfAbsent);
      return joinedMap;
    };
    return AggregatedLogs.builder()
      .ip(joinField.apply(AggregatedLogs::getIp))
      .date(joinField.apply(AggregatedLogs::getDate))
      .method(joinField.apply(AggregatedLogs::getMethod))
      .endpoint(joinField.apply(AggregatedLogs::getEndpoint))
      .protocol(joinField.apply(AggregatedLogs::getProtocol))
      .status(joinField.apply(AggregatedLogs::getStatus))
      .port(joinField.apply(AggregatedLogs::getPort))
      .origin(joinField.apply(AggregatedLogs::getOrigin))
      .userAgent(joinField.apply(AggregatedLogs::getUserAgent))
      .build();
  };

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


