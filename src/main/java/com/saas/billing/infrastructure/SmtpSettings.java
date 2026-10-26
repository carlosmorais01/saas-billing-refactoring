package com.saas.billing.infrastructure;

public record SmtpSettings(String host, int port, String from) {
}
