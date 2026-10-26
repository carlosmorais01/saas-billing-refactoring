package com.saas.billing.port;

import com.saas.billing.domain.Money;

public interface PaymentGateway {

    /** Cobra o valor no cartão; retorna true se o pagamento foi aprovado. */
    boolean charge(String cardToken, Money amount);
}
