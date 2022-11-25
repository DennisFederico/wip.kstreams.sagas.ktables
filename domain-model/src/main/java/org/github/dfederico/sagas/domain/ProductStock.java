package org.github.dfederico.sagas.domain;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Builder
@Data
@Jacksonized
public class ProductStock {
    private String productId;
    private String productName;
    private int availableUnits;
    private int reservedUnits;
    private String reason;

    public boolean reserveAmount(int amount) {
        if (availableUnits >= amount) {
            availableUnits -= amount;
            reservedUnits += amount;
            return true;
        }
        return false;
    }

    public boolean freeReservedAmount(int amount) {
        if (reservedUnits >= amount) {
            reservedUnits -= amount;
            availableUnits += amount;
            return true;
        }
        return false;
    }

    public boolean confirmReservedAmount(int amount) {
        if (reservedUnits >= amount) {
            reservedUnits -= amount;
            return true;
        }
        return false;
    }

}
