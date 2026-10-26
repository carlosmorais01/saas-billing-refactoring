package com.saas.billing.infrastructure;

import com.saas.billing.SubscriptionBillingService;
import java.time.Clock;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Composition root: monta o serviço com os adaptadores reais.
 * Credenciais e endereços vêm de variáveis de ambiente, não do código-fonte.
 */
public final class BillingServiceFactory {

    private BillingServiceFactory() {
    }

    public static SubscriptionBillingService fromEnvironment() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(requiredEnv("BILLING_DB_URL"));
        dataSource.setUser(requiredEnv("BILLING_DB_USER"));
        dataSource.setPassword(requiredEnv("BILLING_DB_PASSWORD"));

        SmtpSettings smtp = new SmtpSettings(
                requiredEnv("BILLING_SMTP_HOST"),
                Integer.parseInt(requiredEnv("BILLING_SMTP_PORT")),
                requiredEnv("BILLING_MAIL_FROM"));

        return new SubscriptionBillingService(
                new JdbcCustomerRepository(dataSource),
                new JdbcInvoiceRepository(dataSource),
                new TokenPaymentGateway(),
                new SmtpNotificationService(smtp),
                Clock.systemDefaultZone());
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória não definida: " + name);
        }
        return value;
    }
}
