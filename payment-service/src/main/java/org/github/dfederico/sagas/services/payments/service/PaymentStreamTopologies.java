package org.github.dfederico.sagas.services.payments.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.Stores;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Customer;
import org.github.dfederico.sagas.domain.Order;

import java.util.Properties;

import static org.github.dfederico.sagas.common.ConfigHelper.KAFKA_CONFIG_PROPERTIES_PREFIX;

@Slf4j
public class PaymentStreamTopologies {

    public static StreamsBuilder buildTopology(StreamsBuilder builder, Properties properties) {
        Serde<Order> orderSerde = ConfigHelper.buildSerde(Order.class);
        Serde<Customer> customerSerde = ConfigHelper.buildSerde(Customer.class);
        String ordersRequestTopic = properties.getProperty("orders.request.topic");
        String ordersResponseTopic = properties.getProperty("orders.response.topic");
        String customerDataTopic = properties.getProperty("customer.data.topic");
        String customerStoreName = properties.getProperty("customer.store.name");

        //CUSTOMERS WITH A NAMED STORE THAT CAN BE EXPOSED BY INTERACTIVE QUERIES
        KeyValueBytesStoreSupplier customerStore = Stores.persistentKeyValueStore(customerStoreName);
        KTable<String, Customer> customerKTable = builder.table(
                customerDataTopic,
                Consumed.with(Serdes.String(), customerSerde),
                Materialized.<String, Customer>as(customerStore)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(customerSerde));

        Properties kafkaProperties = ConfigHelper.filterProperties(properties, KAFKA_CONFIG_PROPERTIES_PREFIX);
        OrderCustomerJoiner orderCustomerJoiner = new OrderCustomerJoiner(ordersResponseTopic, kafkaProperties);

        //THE STREAM WITH THE ORDERS REQUEST
        //TODO BRANCH THE STREAM TO PRODUCE THE ORDER RESPONSE INSTEAD OF PRODUCING ADHOC IN THE JOIN/AGGREGATION
        builder.stream(ordersRequestTopic, Consumed.with(Serdes.Integer(), orderSerde))
                .selectKey((k, v) -> v.getCustomerId())
                .peek((key, order) -> log.info(">>>>> Order Received for customer[{}] - {}:{}", order.getCustomerId(), key, order))
                .leftJoin(customerKTable, orderCustomerJoiner)
                .peek((key, customer) -> log.info(">>>>> JoinResult Id:{}, Available{}, Reserved:{}", customer.getCustomerId(), customer.getAvailableCredit(), customer.getReservedCredit()))
                .to(customerDataTopic, Produced.with(Serdes.String(), customerSerde)); //TODO EVENTUAL CONSISTENCY??
        return builder;
    }


}
