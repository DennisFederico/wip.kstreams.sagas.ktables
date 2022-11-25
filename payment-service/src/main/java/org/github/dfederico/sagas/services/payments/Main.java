package org.github.dfederico.sagas.services.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Customer;
import org.github.dfederico.sagas.services.payments.service.PaymentStreamTopologies;
import spark.Spark;

import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static spark.Spark.*;

@Slf4j
public class Main {

    private static final ObjectMapper objectMapper = ConfigHelper.getObjectMapper();

    public static void main(String[] args) {
        log.info("Loading Application Properties");
        Properties appProps = ConfigHelper.loadApplicationProperties(Paths.get(args[0]));

        log.info("Starting Payments KStream Topology");
        final StreamsBuilder streamsBuilder = new StreamsBuilder();
        PaymentStreamTopologies.buildTopology(streamsBuilder, appProps);
        final Properties kStreamProperties = ConfigHelper.prepareKafkaStreamsProperties(appProps);
        final KafkaStreams kafkaStreams = new KafkaStreams(streamsBuilder.build(), kStreamProperties);
        kafkaStreams.cleanUp();
        kafkaStreams.start();
        Runtime.getRuntime().addShutdownHook(new Thread(kafkaStreams::close));

        //SERVICES AND STORES
        String customerStoreName = appProps.getProperty("customer.store.name");
        final ReadOnlyKeyValueStore<String, Customer> customerStore = kafkaStreams.store(StoreQueryParameters.fromNameAndType(customerStoreName, QueryableStoreTypes.keyValueStore()));

        //WEB SERVER / REQUEST HANDLER
        log.info("Initialize Payments Service");
        initExceptionHandler((e) -> {
            log.error("Exception Starting Server", e);
            System.exit(-1);
        });

        log.info("Starting Payments Service");
        String appPort = appProps.getProperty("spark.port");
        String appPath = appProps.getProperty("app.path");
        port(Integer.parseInt(appPort));
        path("/api", () -> {
            before("/*", "application/json", (request, response) -> log.debug("Received API call {}", request.pathInfo()));
            get(appPath, (request, response) -> {
                response.type("application/json");
                try (KeyValueIterator<String, Customer> customersIterator = customerStore.all()) {
                    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(customersIterator, Spliterator.ORDERED), false)
                            .map(kv -> kv.value)
                            .collect(Collectors.toList());
                }
            }, model -> {
                if (model instanceof List) {
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
                }
                return null;
            });
            get(appPath+"/:customerId", (request, response) -> {
                response.type("application/json");
                String customerId = request.params(":customerId");
                Customer customer = customerStore.get(customerId);
                return customer;
            }, model -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model));
            //Order-Payments History?
            //Pending Order-Payments?
        });
        Runtime.getRuntime().addShutdownHook(new Thread(Spark::stop));
        log.info("Service started on port {}", appPort);

        //TODO PUT IN A THREAD AND ADD SHUTDOWN HOOK
        //paymentService.startPollingOrders();
    }
}