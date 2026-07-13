// SPDX-License-Identifier: MIT
package dev.lodestone.protocol;

public record DeliveryGuarantees(String ordering, String delivery, int bufferLimit) {
    public DeliveryGuarantees {
        if (ordering == null || ordering.isBlank() || delivery == null || delivery.isBlank()) {
            throw new IllegalArgumentException("ordering and delivery must not be blank");
        }
        if (bufferLimit < 1) {
            throw new IllegalArgumentException("bufferLimit must be positive");
        }
    }
}
