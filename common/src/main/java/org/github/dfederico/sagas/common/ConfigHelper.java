package org.github.dfederico.sagas.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.confluent.kafka.serializers.KafkaJsonDeserializer;
import io.confluent.kafka.serializers.KafkaJsonDeserializerConfig;
import io.confluent.kafka.serializers.KafkaJsonSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

public class ConfigHelper {

    public static final String KAFKA_CONFIG_PROPERTIES_PREFIX = "kafka.config.";
    public static final Class<IntegerSerializer> KAFKA_INTEGER_SERIALIZER_CLASS = IntegerSerializer.class;
    public static final Class<KafkaJsonSerializer> CFLT_KAFKA_JSON_SERIALIZER_CLASS = KafkaJsonSerializer.class;

    public static Properties filterProperties(Properties sourceProperties, String matchPrefix) {
        Properties props = new Properties();

        sourceProperties.forEach((key, value) -> {
            if (key instanceof String && ((String) key).startsWith(matchPrefix)) {
                props.put(((String) key).replaceFirst(matchPrefix, ""), value);
            }
        });
        return props;
    }

    public static Properties loadApplicationProperties(Path propertiesPath) {
        Properties appProps = new Properties();
        try {
            appProps = readConfigFile(propertiesPath);
        } catch (Exception e) {
            System.out.println("Provide a configuration property file as argument");
            System.err.printf("Exception while configuring service %s%n", e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
        return appProps;
    }

    private static Properties readConfigFile(Path filepath) throws Exception {
        try (Reader reader = Files.newBufferedReader(filepath)) {
            Properties props = new Properties();
            props.load(reader);
            return props;
        }
    }

    public static Properties prepareKafkaStreamsProperties(Properties applicationProperties) {
        Properties kStreamProperties = new Properties();
        kStreamProperties.putAll(ConfigHelper.filterProperties(applicationProperties, KAFKA_CONFIG_PROPERTIES_PREFIX));
        kStreamProperties.putAll(applicationProperties);
        kStreamProperties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        kStreamProperties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        return kStreamProperties;
    }

    public static ObjectMapper getObjectMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static <T> Serde<T> buildSerde(Class<T> clazz) {
        final KafkaJsonSerializer<T> jsonSerializer = new KafkaJsonSerializer<>();
        jsonSerializer.configure(Collections.singletonMap(KafkaJsonDeserializerConfig.JSON_VALUE_TYPE, clazz.getName()), false);
        final KafkaJsonDeserializer<T> jsonDeserializer = new KafkaJsonDeserializer<>();
        jsonDeserializer.configure(Collections.singletonMap(KafkaJsonDeserializerConfig.JSON_VALUE_TYPE, clazz.getName()), false);
        return Serdes.serdeFrom(jsonSerializer, jsonDeserializer);
    }

    public static <T> Producer<Integer, T> createGenericJsonProducer(Properties connectionProperties) {
        Properties producerProperties = new Properties();
        producerProperties.putAll(connectionProperties);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KAFKA_INTEGER_SERIALIZER_CLASS);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaJsonSerializer.class);
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        //producerProperties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "inventory-orders-tx");
        return new KafkaProducer<>(producerProperties);
    }
}
