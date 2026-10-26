package com.saas.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.InvoiceStatus;
import com.saas.billing.domain.Money;
import com.saas.billing.infrastructure.BillingInfrastructureException;
import com.saas.billing.infrastructure.TokenPaymentGateway;
import com.saas.billing.port.CustomerRepository;
import com.saas.billing.port.InvoiceRepository;
import com.saas.billing.port.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionBillingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate SIGNUP_3_MONTHS_AGO = LocalDate.of(2026, 7, 1);

    private InMemoryCustomerRepository customers;
    private InMemoryInvoiceRepository invoices;
    private RecordingNotificationService notifications;
    private SubscriptionBillingService service;

    @BeforeEach
    void setUp() {
        customers = new InMemoryCustomerRepository();
        invoices = new InMemoryInvoiceRepository();
        notifications = new RecordingNotificationService();
        service = serviceAt(TODAY);
    }

    @Test
    void cobraClienteAtivoGravaFaturaPagaEEnviaRecibo() {
        customers.add(new Customer("c1", "c1@mail.com", "ACTIVE"));

        List<String> logs = service.processMonthlyBilling(command("c1", "tok_123"));

        assertEquals(List.of(
                "Pagamento processado com sucesso: R$ 100.00",
                "E-mail disparado para: c1@mail.com"), logs);
        assertEquals(List.of(new Invoice("c1", Money.of("100.00"), InvoiceStatus.PAID)), invoices.saved);
        assertEquals(List.of("c1@mail.com: R$ 100.00"), notifications.sent);
    }

    @Test
    void falhaNoPagamentoGravaFaturaFalhaEAindaEnviaRecibo() {
        customers.add(new Customer("c1", "c1@mail.com", "ACTIVE"));

        List<String> logs = service.processMonthlyBilling(command("c1", "cartao_invalido"));

        assertEquals("Falha no pagamento do cartão para: c1", logs.get(0));
        assertEquals(InvoiceStatus.FAILED, invoices.saved.get(0).status());
        assertEquals(1, notifications.sent.size());
    }

    @Test
    void tokenNuloEhTratadoComoFalhaDePagamento() {
        customers.add(new Customer("c1", "c1@mail.com", "ACTIVE"));

        service.processMonthlyBilling(command("c1", null));

        assertEquals(InvoiceStatus.FAILED, invoices.saved.get(0).status());
    }

    @Test
    void clienteInativoCancelaCobrancaSemEfeitosColaterais() {
        customers.add(new Customer("c1", "c1@mail.com", "SUSPENDED"));

        List<String> logs = service.processMonthlyBilling(command("c1", "tok_123"));

        assertEquals(List.of("Cliente inativo. Cobrança cancelada: c1"), logs);
        assertTrue(invoices.saved.isEmpty());
        assertTrue(notifications.sent.isEmpty());
    }

    @Test
    void statusAtivoNaoDiferenciaMaiusculas() {
        customers.add(new Customer("c1", "c1@mail.com", "active"));

        service.processMonthlyBilling(command("c1", "tok_123"));

        assertEquals(1, invoices.saved.size());
    }

    @Test
    void clienteInexistenteNaoGeraLogsNemFatura() {
        List<String> logs = service.processMonthlyBilling(command("inexistente", "tok_123"));

        assertTrue(logs.isEmpty());
        assertTrue(invoices.saved.isEmpty());
        assertTrue(notifications.sent.isEmpty());
    }

    @Test
    void cupomBlackFridayDependeDoRelogioInjetado() {
        customers.add(new Customer("c1", "c1@mail.com", "ACTIVE"));
        BillingCommand blackFriday = new BillingCommand(
                "c1", Money.of("100.00"), SIGNUP_3_MONTHS_AGO, 0, "BLACKFRIDAY", false, "tok_1");

        serviceAt(LocalDate.of(2026, 11, 27)).processMonthlyBilling(blackFriday);
        serviceAt(LocalDate.of(2026, 10, 27)).processMonthlyBilling(blackFriday);

        assertEquals(Money.of("70.00"), invoices.saved.get(0).amount());
        assertEquals(Money.of("100.00"), invoices.saved.get(1).amount());
    }

    @Test
    void falhaDeInfraestruturaEhPropagadaEmVezDeSilenciada() {
        BillingInfrastructureException failure =
                new BillingInfrastructureException("banco fora do ar", new RuntimeException());
        CustomerRepository brokenRepository = id -> {
            throw failure;
        };
        SubscriptionBillingService brokenService = new SubscriptionBillingService(
                brokenRepository, invoices, new TokenPaymentGateway(), notifications, fixedClock(TODAY));

        BillingInfrastructureException thrown = assertThrows(BillingInfrastructureException.class,
                () -> brokenService.processMonthlyBilling(command("c1", "tok_123")));

        assertSame(failure, thrown);
    }

    private SubscriptionBillingService serviceAt(LocalDate date) {
        return new SubscriptionBillingService(
                customers, invoices, new TokenPaymentGateway(), notifications, fixedClock(date));
    }

    private static Clock fixedClock(LocalDate date) {
        return Clock.fixed(date.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static BillingCommand command(String customerId, String cardToken) {
        return new BillingCommand(customerId, Money.of("100.00"), SIGNUP_3_MONTHS_AGO, 0, null, false, cardToken);
    }

    private static class InMemoryCustomerRepository implements CustomerRepository {
        private final Map<String, Customer> byId = new HashMap<>();

        void add(Customer customer) {
            byId.put(customer.id(), customer);
        }

        @Override
        public Optional<Customer> findById(String customerId) {
            return Optional.ofNullable(byId.get(customerId));
        }
    }

    private static class InMemoryInvoiceRepository implements InvoiceRepository {
        private final List<Invoice> saved = new ArrayList<>();

        @Override
        public void save(Invoice invoice) {
            saved.add(invoice);
        }
    }

    private static class RecordingNotificationService implements NotificationService {
        private final List<String> sent = new ArrayList<>();

        @Override
        public void sendInvoiceReceipt(String email, Money amount) {
            sent.add(email + ": " + amount);
        }
    }
}
