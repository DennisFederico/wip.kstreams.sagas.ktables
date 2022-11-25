package org.github.dfederico.sagas.services.orders;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Order;
import org.github.dfederico.sagas.domain.OrderGenerator;
import org.github.dfederico.sagas.services.orders.service.KafkaDataLoader;
import org.github.dfederico.sagas.services.orders.service.OrderService;
import org.github.dfederico.sagas.services.orders.service.OrderStreamTopologies;
import spark.Spark;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.github.dfederico.sagas.common.ConfigHelper.KAFKA_CONFIG_PROPERTIES_PREFIX;
import static spark.Spark.*;

@Slf4j
public class Main {
    private static final ObjectMapper objectMapper = ConfigHelper.getObjectMapper();

    public static void main(String[] args) throws Exception {
        log.info("Loading Application Properties");
        Properties appProps = ConfigHelper.loadApplicationProperties(Paths.get(args[0]));

        log.info("Preparing Kafka Topics");
        if (createTopics(appProps)) {
            log.info("Topics Created!");
            initTopicData(appProps);
            log.info("Topic initial dataset published!");
        } else {
            log.info("Topics NOT Created! (already existed)");
        }

        log.info("Starting Order Orchestrator (KStream)");
        final StreamsBuilder streamsBuilder = new StreamsBuilder();
        OrderStreamTopologies.createOrdersStreamOrchestrator(streamsBuilder, appProps);
        OrderStreamTopologies.createOrdersStore(streamsBuilder, appProps);
        final Properties kStreamProperties = OrderStreamTopologies.prepareKafkaStreamsProperties(appProps);
        final KafkaStreams kafkaStreams = new KafkaStreams(streamsBuilder.build(), kStreamProperties);
        kafkaStreams.cleanUp();
        kafkaStreams.start();
        Runtime.getRuntime().addShutdownHook(new Thread(kafkaStreams::close));

        //SERVICES AND STORES
        final ReadOnlyKeyValueStore<Integer, Order> ordersStore = kafkaStreams.store(StoreQueryParameters.fromNameAndType("orders-store", QueryableStoreTypes.keyValueStore()));
        OrderService orderService = new OrderService(appProps);

        //WEB SERVER / REQUEST HANDLER
        log.info("Initialize Orders Service");
        initExceptionHandler((e) -> {
            log.error("Exception Starting Server", e);
            System.exit(-1);
        });

        log.info("Starting Orders Service");
        String appPort = appProps.getProperty("spark.port");
        String appPath = appProps.getProperty("app.path");
        port(Integer.parseInt(appPort));
        path("/api", () -> {
            before("/*", (request, response) -> log.debug("Received API call {}", request.pathInfo()));

            post(appPath, (request, response) -> {
                Order order = OrderGenerator.generateRandomOrder();
                orderService.produceOrder(order);
                return order;
            });

            post(appPath+"/:customer/:product/:quantity/:unit_price", (request, response) -> {
                Order order = OrderGenerator.generateRandomOrder(
                        request.params(":customer"),
                        request.params(":product"),
                        Integer.parseInt(request.params(":quantity")),
                        Integer.parseInt(request.params(":unit_price")));
                orderService.produceOrder(order);
                return order;
            });
            get(appPath+"/:orderId", (request, response) -> {
                response.type("application/json");
                //Integer orderId = Integer.valueOf(request.splat()[0]);
                Integer orderId = Integer.valueOf(request.params(":orderId"));
                Order order = ordersStore.get(orderId);
                return order;
            }, model -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model));

            get(appPath, (request, response) -> {
                response.type("application/json");
                try (KeyValueIterator<Integer, Order> ordersIterator = ordersStore.all()) {
                    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(ordersIterator, Spliterator.ORDERED), false)
                            .map(kv -> kv.value)
                            .collect(Collectors.toList());
                }
            }, model -> {
                if (model instanceof List) {
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
                }
                return null;
            });

        });

        Runtime.getRuntime().addShutdownHook(new Thread(Spark::stop));
        log.info("Service started on port {}", appPort);
    }

    //TODO CHANGE THIS "PER TOPIC", IT MAPS BETTER TO PREPARE EACH TOPIC DATASET
    private static boolean createTopics(Properties appProps) throws Exception {
        Properties connectionProps = ConfigHelper.filterProperties(appProps, KAFKA_CONFIG_PROPERTIES_PREFIX);
        try (Admin kafkaAdmin = Admin.create(connectionProps)) {
            // WHICH TOPICS NEED CREATING?
            List<String> neededTopics = appProps.stringPropertyNames()
                    .stream()
                    .filter(property -> property.endsWith(".topic"))
                    .map(appProps::getProperty)
                    .collect(Collectors.toList());

            List<String> topicsToCreate = new ArrayList<>();

            DescribeTopicsResult describeResults = kafkaAdmin.describeTopics(neededTopics);
            describeResults.topicNameValues().forEach((topicName, future) -> {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                        topicsToCreate.add(topicName);
                    } else {
                        throw new RuntimeException(e);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            if (!topicsToCreate.isEmpty()) {
                //FETCH ONE NODE_ID
                String brokerId = kafkaAdmin.describeCluster()
                        .nodes()
                        .get(15, TimeUnit.SECONDS).stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Cannot fetch cluster brokerId"))
                        .idString();

                //WHICH REPLICATION FACTOR?
                ConfigResource brokerResource = new ConfigResource(ConfigResource.Type.BROKER, brokerId);
                Map<ConfigResource, Config> configs = kafkaAdmin
                        .describeConfigs(Collections.singletonList(brokerResource))
                        .all()
                        .get(15, TimeUnit.SECONDS);
                //configs.get(brokerResource).get("min.insync.replicas");
                short defaultRFValue = Short.parseShort(configs.get(brokerResource).get("default.replication.factor").value());

                Map<String, String> deletePolicyConfig = Collections.singletonMap("cleanup.policy", "delete");
                //CONFIGURE TOPICS
                int numPartitions = Integer.parseInt(appProps.getProperty("num.partitions.per_topic", "1"));
                List<NewTopic> newTopics = topicsToCreate.stream()
                        .map(s -> {
                            NewTopic newTopic = new NewTopic(s, numPartitions, defaultRFValue);
                            newTopic.configs(deletePolicyConfig);
                            return newTopic;
                        })
                        .collect(Collectors.toList());

                //CREATE TOPICS
                CreateTopicsResult topicsResults = kafkaAdmin.createTopics(newTopics);
                topicsResults.all().get(60, TimeUnit.SECONDS);
                return true;
            }
            return false;
        }
    }

    private static void initTopicData(Properties appProps) {
        Properties connectionProperties = ConfigHelper.filterProperties(appProps, KAFKA_CONFIG_PROPERTIES_PREFIX);

        String stockTopic = appProps.getProperty("inventory.data.topic");
        KafkaDataLoader.loadStockData(connectionProperties, stockTopic);

        String customerTopic = appProps.getProperty("customer.data.topic");
        KafkaDataLoader.loadCustomerData(connectionProperties, customerTopic);
    }
}