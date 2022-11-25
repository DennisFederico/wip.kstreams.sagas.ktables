package org.github.dfederico.sagas.services.payments.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.github.dfederico.sagas.common.ConfigHelper;
import org.github.dfederico.sagas.domain.Customer;
import org.github.dfederico.sagas.domain.Order;

import java.util.Properties;
import java.util.function.BiConsumer;

@Slf4j
public class OrderCustomerJoiner implements ValueJoiner<Order, Customer, Customer> {

    private final static String SOURCE = "PAYMENTS";
    private final String rejectOrderTopic;
    private final Producer<Integer, Order> orderResponseProducer;

    public OrderCustomerJoiner(String rejectOrderTopic, Properties properties) {
        this.rejectOrderTopic = rejectOrderTopic;
        orderResponseProducer = ConfigHelper.createGenericJsonProducer(properties);
    }

    @Override
    public Customer apply(Order order, Customer customer) {
        switch (order.getStatus()) {
            case "NEW":
                processFundsReservation.accept(order, customer);
                //TODO... BRANCH OR SEND FROM CONTEXT
                sendOrderResponse(order, orderResponseProducer, rejectOrderTopic);
                break;
            case "CONFIRMED":
                confirmPayment.accept(order, customer);
                break;
            case "COMPENSATE":
                if (!order.getSource().equals(SOURCE)) compensateFundsReservation.accept(order, customer);
                break;
        }
        return customer;
    }

    static BiConsumer<Order, Customer> processFundsReservation = (order, customer) -> {
        int amount = order.getUnits() * order.getUnitPrice();
        log.info(">>> Process Customer Funds Reservation [OrderId:{} CustomerId:{} Amount:{}]", order.getId(), order.getCustomerId(), amount);

        boolean reserved = customer.reservePayment(amount);
        if (reserved) {
            order.approveOrder(SOURCE);
        } else {
            order.rejectOrder(SOURCE, "Not enough credits available for payment");
        }
        log.info(">>> Process Customer Funds Reservation - Result:{} | Customer:[Id:{}, Available:{}, Reserved:{}]", reserved, customer.getCustomerId(), customer.getAvailableCredit(), customer.getReservedCredit());
    };

    static BiConsumer<Order, Customer> confirmPayment = (order, customer) -> {
        int amount = order.getUnits() * order.getUnitPrice();
        log.info(">>> Confirm Customer Payment [OrderId:{} CustomerId:{} Amount:{}]", order.getId(), order.getCustomerId(), amount);
        boolean confirmed = customer.confirmPayment(amount);
        log.info(">>> Confirm Customer Payment - Result:{} | Customer:[Id:{}, Available:{}, Reserved:{}]", confirmed, customer.getCustomerId(), customer.getAvailableCredit(), customer.getReservedCredit());
    };

    static BiConsumer<Order, Customer> compensateFundsReservation = (order, customer) -> {
        int amount = order.getUnits() * order.getUnitPrice();
        log.info(">>> Compensate Customer Funds Reservation [OrderId:{} CustomerId:{} Amount:{}]", order.getId(), order.getCustomerId(), amount);
        boolean compensated = customer.freeReservedPayment(amount);
        log.info(">>> Compensate Customer Funds Reservation - Result:{} | Customer:[Id:{}, Available:{}, Reserved:{}]", compensated, customer.getCustomerId(), customer.getAvailableCredit(), customer.getReservedCredit());
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
