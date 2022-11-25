package org.github.dfederico.sagas.services.inventory.service;

import io.confluent.kafka.serializers.KafkaJsonDeserializer;
import io.confluent.kafka.serializers.KafkaJsonDeserializerConfig;
import io.confluent.kafka.serializers.KafkaJsonSerializer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.Stores;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Order;
import org.github.dfederico.sagas.domain.ProductStock;

import java.util.Collections;
import java.util.Properties;

import static org.github.dfederico.sagas.common.ConfigHelper.KAFKA_CONFIG_PROPERTIES_PREFIX;

@Slf4j
public class InventoryStreamTopologies {

    public static StreamsBuilder buildTopology(StreamsBuilder builder, Properties properties) {

        Serde<Order> orderSerde = ConfigHelper.buildSerde(Order.class);
        Serde<ProductStock> productStockSerde = ConfigHelper.buildSerde(ProductStock.class);
        String ordersRequestTopic = properties.getProperty("orders.request.topic");
        String ordersResponseTopic = properties.getProperty("orders.response.topic");
        String inventoryDataTopic = properties.getProperty("inventory.data.topic");
        String inventoryStoreName = properties.getProperty("inventory.store.name");

        //INVENTORY WITH A NAMED STORE THAT CAN BE EXPOSED BY INTERACTIVE QUERIES
        KeyValueBytesStoreSupplier inventoryStore = Stores.persistentKeyValueStore(inventoryStoreName);
        KTable<String, ProductStock> inventoryKTable = builder.table(
                inventoryDataTopic,
                Consumed.with(Serdes.String(), productStockSerde),
                Materialized.<String, ProductStock>as(inventoryStore)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(productStockSerde));

        Properties kafkaProperties = ConfigHelper.filterProperties(properties, KAFKA_CONFIG_PROPERTIES_PREFIX);
        OrderProductJoiner orderProductJoiner = new OrderProductJoiner(ordersResponseTopic, kafkaProperties);

        //THE STREAM WITH THE ORDERS REQUEST
        //TODO CHECK CO-GROUPINGS FOR MULTIPLE ITEMS IN THE ORDER
        //TODO BRANCH THE STREAM TO PRODUCE THE ORDER RESPONSE INSTEAD OF PRODUCING ADHOC IN THE JOIN/AGGREGATION
        builder.stream(ordersRequestTopic, Consumed.with(Serdes.Integer(), orderSerde))
                .selectKey((k, v) -> v.getProductId())
                .peek((key, order) -> log.info(">>>>> Order Received for product[{}] - {}:{}", order.getProductId(), key, order))
                .leftJoin(inventoryKTable, orderProductJoiner)
                .peek((key, product) -> log.info(">>>>> JoinResult Id:{}, Available{}, Reserved:{}", product.getProductId(), product.getAvailableUnits(), product.getReservedUnits()))
                .to(inventoryDataTopic, Produced.with(Serdes.String(), productStockSerde)); //TODO EVENTUAL CONSISTENCY??
        return builder;
    }
}
