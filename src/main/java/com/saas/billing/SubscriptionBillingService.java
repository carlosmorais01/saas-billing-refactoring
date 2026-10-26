package com.saas.billing;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Money;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.mail.SimpleEmail;

public class SubscriptionBillingService {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/saas_db";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "admin123";

    public List<String> processMonthlyBilling(BillingCommand command) {

        List<String> logs = new ArrayList<>();

        LocalDate signupDate = command.signupDate();
        LocalDate today = LocalDate.now();

        int monthsActive = (today.getYear() - signupDate.getYear()) * 12 + (today.getMonthValue() - signupDate.getMonthValue());

        Money basePrice = command.basePrice();
        Money calculatedPrice = basePrice;

        if (monthsActive > 24) {
            calculatedPrice = calculatedPrice.minus(basePrice.times("0.15")); // 15% após 2 anos
        } else if (monthsActive > 12) {
            calculatedPrice = calculatedPrice.minus(basePrice.times("0.10")); // 10% após 1 ano
        }

        if (command.nonProfit()) {
            calculatedPrice = calculatedPrice.times("0.70"); // 30% off para ONGs
        }

        if (command.hasPromoCode()) {
            String promoCode = command.promoCode();
            if (promoCode.equalsIgnoreCase("BLACKFRIDAY") && today.getMonthValue() == 11) {
                calculatedPrice = calculatedPrice.minus(Money.of("30.00"));
            } else if (promoCode.equalsIgnoreCase("WELCOME10") && monthsActive <= 1) {
                calculatedPrice = calculatedPrice.times("0.90");
            }
        }

        int overdueDays = command.overdueDays();
        if (overdueDays > 0) {
            Money penalty = calculatedPrice.times("0.02");
            Money dailyInterest = calculatedPrice.times("0.001").times(overdueDays);
            calculatedPrice = calculatedPrice.plus(penalty).plus(dailyInterest);
        }

        calculatedPrice = calculatedPrice.atLeastZero().roundedToCents();

        String customerId = command.customerId();
        String paymentCardToken = command.paymentCardToken();

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            String sqlCustomer = "SELECT email, status FROM customers WHERE id = ?";
            PreparedStatement stmt = conn.prepareStatement(sqlCustomer);
            stmt.setString(1, customerId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String email = rs.getString("email");
                String status = rs.getString("status");

                if (!"ACTIVE".equalsIgnoreCase(status)) {
                    logs.add("Cliente inativo. Cobrança cancelada: " + customerId);
                    return logs;
                }

                boolean paymentSuccess = false;
                if (paymentCardToken != null && paymentCardToken.startsWith("tok_")) {
                    paymentSuccess = true;
                    logs.add("Pagamento processado com sucesso: " + calculatedPrice);
                } else {
                    logs.add("Falha no pagamento do cartão para: " + customerId);
                }

                String sqlInvoice = "INSERT INTO invoices(customer_id, amount, status, created_at) VALUES (?, ?, ?, NOW())";
                PreparedStatement stmtInvoice = conn.prepareStatement(sqlInvoice);
                stmtInvoice.setString(1, customerId);
                stmtInvoice.setBigDecimal(2, calculatedPrice.toCents());
                stmtInvoice.setString(3, paymentSuccess ? "PAID" : "FAILED");
                stmtInvoice.executeUpdate();

                SimpleEmail emailSender = new SimpleEmail();
                emailSender.setHostName("smtp.empresa.com");
                emailSender.setSmtpPort(587);
                emailSender.setFrom("billing@empresa.com");
                emailSender.setSubject("Sua Fatura Mensal");
                emailSender.setMsg("Olá! Sua fatura foi processada no valor de " + calculatedPrice);
                emailSender.addTo(email);
                // emailSender.send();

                logs.add("E-mail disparado para: " + email);
            }
        } catch (Exception e) {
            logs.add("Erro fatal no processamento: " + e.getMessage());
        }

        return logs;
    }
}
