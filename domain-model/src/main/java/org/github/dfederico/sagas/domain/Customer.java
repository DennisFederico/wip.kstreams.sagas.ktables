package org.github.dfederico.sagas.domain;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Builder
@Data
@Jacksonized
public class Customer {
    private String customerId;
    private String name;
    private int availableCredit;
    private int reservedCredit;

    public boolean reservePayment(int amount) {
        if (availableCredit >= amount) {
            availableCredit -= amount;
            reservedCredit += amount;
            return true;
        }
        return false;
    }

    public boolean freeReservedPayment(int amount) {
        if (reservedCredit >= amount) {
            reservedCredit -= amount;
            availableCredit += amount;
            return true;
        }
        return false;
    }

    public boolean confirmPayment(int amount) {
        if (reservedCredit >= amount) {
            reservedCredit -= amount;
            return true;
        }
        return false;
    }
}
