package com.saas.billing;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.InvoiceStatus;
import com.saas.billing.domain.Money;
import com.saas.billing.domain.PricingCalculator;
import com.saas.billing.port.CustomerRepository;
import com.saas.billing.port.InvoiceRepository;
import com.saas.billing.port.NotificationService;
import com.saas.billing.port.PaymentGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fachada da cobrança mensal: orquestra precificação, pagamento, faturamento
 * e notificação. Não contém regras de preço nem acesso direto a I/O.
 */
public class SubscriptionBillingService {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;
    private final PricingCalculator pricingCalculator;
    private final Clock clock;

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      InvoiceRepository invoiceRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock) {
        this(customerRepository, invoiceRepository, paymentGateway, notificationService,
                new PricingCalculator(), clock);
    }

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      InvoiceRepository invoiceRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      PricingCalculator pricingCalculator,
                                      Clock clock) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.pricingCalculator = pricingCalculator;
        this.clock = clock;
    }

    public List<String> processMonthlyBilling(BillingCommand command) {
        Money amount = pricingCalculator.calculate(command, LocalDate.now(clock));
        Optional<Customer> customer = customerRepository.findById(command.customerId());

        if (customer.isEmpty()) {
            return new ArrayList<>();
        }
        if (!customer.get().isActive()) {
            return new ArrayList<>(List.of("Cliente inativo. Cobrança cancelada: " + command.customerId()));
        }
        return charge(customer.get(), command.paymentCardToken(), amount);
    }

    private List<String> charge(Customer customer, String cardToken, Money amount) {
        List<String> logs = new ArrayList<>();
        boolean paid = paymentGateway.charge(cardToken, amount);
        logs.add(paid
                ? "Pagamento processado com sucesso: " + amount
                : "Falha no pagamento do cartão para: " + customer.id());

        invoiceRepository.save(new Invoice(customer.id(), amount, paid ? InvoiceStatus.PAID : InvoiceStatus.FAILED));

        notificationService.sendInvoiceReceipt(customer.email(), amount);
        logs.add("E-mail disparado para: " + customer.email());
        return logs;
    }
}
