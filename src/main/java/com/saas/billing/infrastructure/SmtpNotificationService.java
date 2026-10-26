package com.saas.billing.infrastructure;

import com.saas.billing.domain.Money;
import com.saas.billing.port.NotificationService;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;

public class SmtpNotificationService implements NotificationService {

    private static final String SUBJECT = "Sua Fatura Mensal";

    private final SmtpSettings settings;

    public SmtpNotificationService(SmtpSettings settings) {
        this.settings = settings;
    }

    @Override
    public void sendInvoiceReceipt(String email, Money amount) {
        try {
            SimpleEmail message = new SimpleEmail();
            message.setHostName(settings.host());
            message.setSmtpPort(settings.port());
            message.setFrom(settings.from());
            message.setSubject(SUBJECT);
            message.setMsg("Olá! Sua fatura foi processada no valor de " + amount);
            message.addTo(email);
            message.send();
        } catch (EmailException e) {
            throw new BillingInfrastructureException("Erro ao enviar recibo para " + email, e);
        }
    }
}
