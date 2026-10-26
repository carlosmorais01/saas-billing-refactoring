package com.saas.billing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingCalculatorTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 15);
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 27);

    private final PricingCalculator calculator = new PricingCalculator();

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // cenário,                          base,   adesão,     atraso, cupom,        ONG,   hoje,       esperado
            "'sem descontos',                    100.00, 2026-07-01, 0,      ,             false, 2026-10-15, 100.00",
            "'fidelidade > 12 meses: 10%',       100.00, 2025-07-01, 0,      ,             false, 2026-10-15, 90.00",
            "'exatamente 12 meses: sem desconto',100.00, 2025-10-01, 0,      ,             false, 2026-10-15, 100.00",
            "'fidelidade > 24 meses: 15%',       100.00, 2024-07-01, 0,      ,             false, 2026-10-15, 85.00",
            "'exatamente 24 meses: 10%',         100.00, 2024-10-01, 0,      ,             false, 2026-10-15, 90.00",
            "'ONG: 30% off',                     100.00, 2026-07-01, 0,      ,             true,  2026-10-15, 70.00",
            "'fidelidade 24m + ONG',             100.00, 2024-07-01, 0,      ,             true,  2026-10-15, 59.50",
            "'BLACKFRIDAY em novembro',          100.00, 2026-07-01, 0,      BLACKFRIDAY,  false, 2026-11-27, 70.00",
            "'BLACKFRIDAY fora de novembro',     100.00, 2026-07-01, 0,      BLACKFRIDAY,  false, 2026-10-15, 100.00",
            "'cupom ignora maiúsculas',          100.00, 2026-07-01, 0,      blackfriday,  false, 2026-11-27, 70.00",
            "'WELCOME10 no 1º mês',              100.00, 2026-10-01, 0,      WELCOME10,    false, 2026-10-15, 90.00",
            "'WELCOME10 com 1 mês',              100.00, 2026-09-30, 0,      WELCOME10,    false, 2026-10-15, 90.00",
            "'WELCOME10 após 1 mês',             100.00, 2026-08-01, 0,      WELCOME10,    false, 2026-10-15, 100.00",
            "'cupom desconhecido',               100.00, 2026-07-01, 0,      XPTO,         false, 2026-10-15, 100.00",
            "'cupom em branco',                  100.00, 2026-07-01, 0,      '   ',        false, 2026-10-15, 100.00",
            "'atraso de 10 dias: 2% + 0,1%/dia', 100.00, 2026-07-01, 10,     ,             false, 2026-10-15, 103.00",
            "'atraso negativo é ignorado',       100.00, 2026-07-01, -5,     ,             false, 2026-10-15, 100.00",
            "'BLACKFRIDAY não deixa negativo',   20.00,  2026-07-01, 0,      BLACKFRIDAY,  false, 2026-11-27, 0.00",
            "'negativo com atraso vira zero',    20.00,  2026-07-01, 30,     BLACKFRIDAY,  false, 2026-11-27, 0.00",
            "'arredonda para centavos',          99.90,  2024-07-01, 7,      ,             true,  2026-10-15, 61.05",
    })
    void aplicaRegrasDePrecificacao(String scenario, String basePrice, LocalDate signup, int overdueDays,
                                    String promoCode, boolean nonProfit, LocalDate today, String expected) {
        BillingCommand command = new BillingCommand(
                "c1", Money.of(basePrice), signup, overdueDays, promoCode, nonProfit, "tok_1");

        assertEquals(Money.of(expected), calculator.calculate(command, today));
    }

    @Test
    void mesesAtivosIgnoramODiaDoMes() {
        assertEquals(1, calculator.monthsActive(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)));
        assertEquals(0, calculator.monthsActive(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
    }

    /**
     * Golden master: compara a nova implementação com a fórmula legada (em double)
     * em todas as combinações de entradas. A única diferença aceita é o arredondamento
     * para centavos que o legado não fazia: no máximo R$ 0,01.
     */
    @Test
    void preservaComportamentoDaFormulaLegada() {
        List<String> divergences = new ArrayList<>();
        BigDecimal maxDifference = BigDecimal.ZERO;
        int combinations = 0;
        int centDifferences = 0;
        String centExample = "-";

        for (String base : List.of("0.00", "9.90", "19.99", "49.90", "99.90", "100.00", "149.99", "1234.56")) {
            for (LocalDate signup : List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 15),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2025, 10, 1), LocalDate.of(2025, 9, 1),
                    LocalDate.of(2024, 10, 1), LocalDate.of(2024, 9, 1), LocalDate.of(2020, 1, 1))) {
                for (int overdueDays : new int[] {-1, 0, 1, 7, 30, 365}) {
                    for (String promo : Arrays.asList(null, "", "BLACKFRIDAY", "welcome10", "XPTO")) {
                        for (boolean nonProfit : new boolean[] {false, true}) {
                            for (LocalDate today : List.of(OCTOBER, NOVEMBER)) {
                                combinations++;
                                double legacy = LegacyPricingFormula.calculate(
                                        Double.parseDouble(base), signup, overdueDays, promo, nonProfit, today);
                                Money actual = calculator.calculate(new BillingCommand(
                                        "c1", Money.of(base), signup, overdueDays, promo, nonProfit, "tok_1"), today);

                                BigDecimal difference = BigDecimal.valueOf(legacy)
                                        .setScale(2, RoundingMode.HALF_UP)
                                        .subtract(actual.toCents()).abs();
                                maxDifference = maxDifference.max(difference);
                                if (difference.signum() > 0 && centDifferences++ == 0) {
                                    centExample = base + " " + signup + " atraso=" + overdueDays + " " + promo
                                            + " ONG=" + nonProfit + " " + today + ": legado=" + legacy + " novo=" + actual;
                                }
                                if (difference.compareTo(new BigDecimal("0.01")) > 0) {
                                    divergences.add(base + " " + signup + " " + overdueDays + " " + promo
                                            + " " + nonProfit + " " + today + ": legado=" + legacy + " novo=" + actual);
                                }
                            }
                        }
                    }
                }
            }
        }

        assertTrue(divergences.isEmpty(), "Divergências: " + divergences);
        System.out.println("Golden master: " + combinations + " combinações, diferença máxima = R$ " + maxDifference
                + ", casos com diferença de centavo = " + centDifferences + " (ex.: " + centExample + ")");
    }
}
