package com.saas.billing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void valoresComEscalasDiferentesSaoIguais() {
        assertEquals(Money.of("10.0"), Money.of("10.00"));
        assertEquals(Money.of("10.0").hashCode(), Money.of("10.00").hashCode());
    }

    @Test
    void operacoesNaoAlteramAInstanciaOriginal() {
        Money original = Money.of("100.00");

        original.plus(Money.of("1.00"));
        original.minus(Money.of("1.00"));
        original.times("0.5");

        assertEquals(Money.of("100.00"), original);
    }

    @Test
    void evitaErroDePontoFlutuante() {
        // Em double: 0.1 + 0.2 == 0.30000000000000004
        assertEquals(Money.of("0.30"), Money.of("0.1").plus(Money.of("0.2")));
    }

    @Test
    void multiplicacaoPorFatorDecimalEhExata() {
        assertEquals(Money.of("84.915"), Money.of("99.90").times("0.85"));
    }

    @Test
    void arredondaParaCentavosComHalfUp() {
        assertEquals(Money.of("84.92"), Money.of("84.915").roundedToCents());
        assertEquals(Money.of("84.91"), Money.of("84.914").roundedToCents());
        assertEquals(new BigDecimal("84.92"), Money.of("84.915").toCents());
    }

    @Test
    void atLeastZeroTransformaNegativoEmZero() {
        Money negativo = Money.of("-5.00");

        assertTrue(negativo.isNegative());
        assertEquals(Money.ZERO, negativo.atLeastZero());
    }

    @Test
    void atLeastZeroMantemValorPositivo() {
        Money positivo = Money.of("5.00");

        assertSame(positivo, positivo.atLeastZero());
    }

    @Test
    void toStringExibeEmReaisComDuasCasas() {
        assertEquals("R$ 85.00", Money.of("85").toString());
        assertEquals("R$ 0.00", Money.ZERO.toString());
    }

    @Test
    void naoAceitaValorNulo() {
        assertThrows(NullPointerException.class, () -> new Money(null));
    }
}
