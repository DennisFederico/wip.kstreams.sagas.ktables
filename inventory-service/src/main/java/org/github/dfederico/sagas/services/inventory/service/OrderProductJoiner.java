package org.github.dfederico.sagas.services.inventory.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Order;
import org.github.dfederico.sagas.domain.ProductStock;

import java.util.Properties;
import java.util.function.BiConsumer;

@Slf4j
public class OrderProductJoiner implements ValueJoiner<Order, ProductStock, ProductStock> {

    private final static String SOURCE = "INVENTORY";
    private final String rejectOrderTopic;
    private final Producer<Integer, Order> orderResponseProducer;

    public OrderProductJoiner(String rejectOrderTopic, Properties properties) {
        this.rejectOrderTopic = rejectOrderTopic;
        orderResponseProducer = ConfigHelper.createGenericJsonProducer(properties);
    }

    @Override
    public ProductStock apply(Order order, ProductStock product) {
        switch (order.getStatus()) {
            case "NEW":
                processProductStockReservation.accept(order, product);
                //TODO... BRANCH OR SEND FROM CONTEXT
                sendOrderResponse(order, orderResponseProducer, rejectOrderTopic);
                break;
            case "CONFIRMED":
                confirmProductStockReservation.accept(order, product);
                break;
            case "COMPENSATE":
                if (!order.getSource().equals(SOURCE)) compensateProductStockReservation.accept(order, product);
                break;
        }
        return product;
    }

    static BiConsumer<Order, ProductStock> processProductStockReservation = (order, productStock) -> {
        log.info(">>> Process ProductStock Reservation [OrderId:{} ProductId:{} Amount:{}]", order.getId(), order.getProductId(), order.getUnits());
        boolean reserved = productStock.reserveAmount(order.getUnits(), String.format("for Order %d - Customer %s", order.getId(), order.getCustomerId()));
        if (reserved) {
            order.approveOrder(SOURCE);
        } else {
            order.rejectOrder(SOURCE, "Not enough product units available");
        }
        log.info(">>> Process ProductStock Reservation - Result:{} | Product:[Id:{}, Available:{}, Reserved:{}]", reserved, productStock.getProductId(), productStock.getAvailableUnits(), productStock.getReservedUnits());
    };

    static BiConsumer<Order, ProductStock> confirmProductStockReservation = (order, productStock) -> {
        log.info(">>> Confirm ProductStock Reservation [OrderId:{} ProductId:{} Amount:{}]", order.getId(), order.getProductId(), order.getUnits());
        boolean confirmed = productStock.confirmReservedAmount(order.getUnits(), String.format("for Order %d - Customer %s", order.getId(), order.getCustomerId()));
        log.info(">>> Confirm ProductStock Reservation - Result:{} | Product:[Id:{}, Available:{}, Reserved:{}]", confirmed, productStock.getProductId(), productStock.getAvailableUnits(), productStock.getReservedUnits());
    };

    static BiConsumer<Order, ProductStock> compensateProductStockReservation = (order, productStock) -> {
        log.info(">>> Compensate ProductStock Reservation [OrderId:{} ProductId:{} Amount:{}]", order.getId(), order.getProductId(), order.getUnits());
        boolean compensated = productStock.freeReservedAmount(order.getUnits(), String.format("for Order %d - Customer %s", order.getId(), order.getCustomerId()));
        log.info(">>> Compensate ProductStock Reservation - Result:{} | Product:[Id:{}, Available:{}, Reserved:{}]", compensated, productStock.getProductId(), productStock.getAvailableUnits(), productStock.getReservedUnits());
    };

    private static void sendOrderResponse(Order order, Producer<Integer, Order> orderResponseProducer, String rejectOrderTopic) {
        ProducerRecord<Integer, Order> record = new ProducerRecord<>(rejectOrderTopic, order.getId(), order);

        orderResponseProducer.send(record, (recordMetadata, exception) -> {
            if (exception == null) {
                log.info("Produced record to P:{} O:{} - K:{}, V:{} @timestamp {}",
                        recordMetadata.partition(),
                        recordMetadata.offset(),
                        record.key(),
                        record.value(),
                        recordMetadata.timestamp());
            } else {
                //orderResponseProducer.abortTransaction(); //TODO NOT IN A TRANSACTION CONTEXT
                log.error("An error occurred while producing an event '{}'", exception.getMessage());
                exception.printStackTrace(System.err);
            }
        });
    }
}
