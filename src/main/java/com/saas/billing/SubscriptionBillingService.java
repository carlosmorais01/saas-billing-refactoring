package com.saas.billing;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.InvoiceStatus;
import com.saas.billing.domain.Money;
import com.saas.billing.port.CustomerRepository;
import com.saas.billing.port.InvoiceRepository;
import com.saas.billing.port.NotificationService;
import com.saas.billing.port.PaymentGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SubscriptionBillingService {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;
    private final Clock clock;

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      InvoiceRepository invoiceRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public List<String> processMonthlyBilling(BillingCommand command) {

        List<String> logs = new ArrayList<>();

        LocalDate signupDate = command.signupDate();
        LocalDate today = LocalDate.now(clock);

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
        Optional<Customer> found = customerRepository.findById(customerId);

        if (found.isPresent()) {
            Customer customer = found.get();

            if (!customer.isActive()) {
                logs.add("Cliente inativo. Cobrança cancelada: " + customerId);
                return logs;
            }

            boolean paymentSuccess = paymentGateway.charge(command.paymentCardToken(), calculatedPrice);
            if (paymentSuccess) {
                logs.add("Pagamento processado com sucesso: " + calculatedPrice);
            } else {
                logs.add("Falha no pagamento do cartão para: " + customerId);
            }

            invoiceRepository.save(new Invoice(customerId, calculatedPrice,
                    paymentSuccess ? InvoiceStatus.PAID : InvoiceStatus.FAILED));

            notificationService.sendInvoiceReceipt(customer.email(), calculatedPrice);
            logs.add("E-mail disparado para: " + customer.email());
        }

        return logs;
    }
}
