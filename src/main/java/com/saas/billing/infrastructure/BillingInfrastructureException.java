package com.saas.billing.infrastructure;

/** Falha em um serviço externo (banco, SMTP), propagada com a causa original. */
public class BillingInfrastructureException extends RuntimeException {

    public BillingInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
