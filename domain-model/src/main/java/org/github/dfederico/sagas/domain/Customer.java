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
    private String reason;

    public boolean reservePayment(int amount, String reason) {
        if (availableCredit >= amount) {
            availableCredit -= amount;
            reservedCredit += amount;
            this.reason = reason;
            return true;
        }
        return false;
    }

    public boolean freeReservedPayment(int amount, String reason) {
        if (reservedCredit >= amount) {
            reservedCredit -= amount;
            availableCredit += amount;
            this.reason = reason;
            return true;
        }
        return false;
    }

    public boolean confirmPayment(int amount, String reason) {
        if (reservedCredit >= amount) {
            reservedCredit -= amount;
            this.reason = reason;
            return true;
        }
        return false;
    }
}
