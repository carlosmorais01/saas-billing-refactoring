package com.saas.billing.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Parameter Object com os dados de entrada de uma cobrança mensal.
 * Substitui a lista de 8 parâmetros primitivos do método legado
 * (o parâmetro planType, que nunca era lido, foi removido).
 */
public record BillingCommand(
        String customerId,
        Money basePrice,
        LocalDate signupDate,
        int overdueDays,
        String promoCode,
        boolean nonProfit,
        String paymentCardToken) {

    public BillingCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(basePrice, "basePrice");
        Objects.requireNonNull(signupDate, "signupDate");
    }

    public boolean hasPromoCode() {
        return promoCode != null && !promoCode.trim().isEmpty();
    }
}
