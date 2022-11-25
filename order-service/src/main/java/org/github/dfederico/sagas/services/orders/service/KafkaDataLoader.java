package org.github.dfederico.sagas.services.orders.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.Serializer;
import org.github.dfederico.sagas.domain.Customer;
import org.github.dfederico.sagas.domain.CustomerGenerator;
import org.github.dfederico.sagas.domain.ProductStock;
import org.github.dfederico.sagas.domain.ProductStockGenerator;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
public class KafkaDataLoader {

    private static <K, V> Producer<K, V> createKafkaJsonProducer(Properties connectionProperties, Class<? extends Serializer> keySerializer, Class<? extends Serializer> valueSerializer) {
        Properties producerProperties = new Properties();
        producerProperties.putAll(connectionProperties);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(CommonClientConfigs.CLIENT_ID_CONFIG, "DataLoader-1");
        return new KafkaProducer<>(producerProperties);
    }

    private static <K, V> Future<RecordMetadata> produceData(Producer<K, V> producer, String topicName, K key, V valueObject) {
        ProducerRecord<K, V> record = new ProducerRecord<>(topicName, key, valueObject);
        return producer.send(record, (recordMetadata, exception) -> {
            if (exception == null) {
                log.info("Produced record to P:{} O:{} - K:{}, V:{} @timestamp {}",
                        recordMetadata.partition(),
                        recordMetadata.offset(),
                        record.key(),
                        record.value(),
                        recordMetadata.timestamp());
            } else {
                log.error("An error occurred while producing an event '{}'", exception.getMessage());
                exception.printStackTrace(System.err);
            }
        });
    }

    public static void loadStockData(Properties connectionProperties, String productTopic) {
        try (Producer<String, ProductStock> producer = createKafkaJsonProducer(connectionProperties,
                org.apache.kafka.common.serialization.StringSerializer.class,
                io.confluent.kafka.serializers.KafkaJsonSerializer.class)) {
            Map<String, ProductStock> products = ProductStockGenerator.createProductStockRepository();
            List<Future<RecordMetadata>> kafkaRecords = products.entrySet().stream()
                    .map((entry) -> produceData(producer, productTopic, entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
        }
    }

    public static void loadCustomerData(Properties connectionProperties, String customerTopic) {
        try (Producer<String, Customer> producer = createKafkaJsonProducer(connectionProperties,
                org.apache.kafka.common.serialization.StringSerializer.class,
                io.confluent.kafka.serializers.KafkaJsonSerializer.class)) {
            Map<String, Customer> customers = CustomerGenerator.createCustomerRepository();
            List<Future<RecordMetadata>> kafkaRecords = customers.entrySet().stream()
                    .map((entry) -> produceData(producer, customerTopic, entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
        }
    }
}
