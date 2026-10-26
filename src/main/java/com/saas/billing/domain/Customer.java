package com.saas.billing.domain;

import java.util.Objects;

public record Customer(String id, String email, String status) {

    private static final String ACTIVE_STATUS = "ACTIVE";

    public Customer {
        Objects.requireNonNull(id, "id");
    }

    public boolean isActive() {
        return ACTIVE_STATUS.equalsIgnoreCase(status);
    }
}
