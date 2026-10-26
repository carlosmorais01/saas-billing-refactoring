package com.saas.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object imutável para valores monetários em reais.
 *
 * As operações usam BigDecimal sem arredondamento intermediário (todos os fatores
 * de negócio são decimais exatos); o arredondamento para centavos é feito apenas
 * em {@link #roundedToCents()}, em um único ponto.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.stripTrailingZeros();
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money of(double amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(String factor) {
        return times(new BigDecimal(factor));
    }

    public Money times(int multiplier) {
        return times(BigDecimal.valueOf(multiplier));
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** Retorna este valor, ou zero se ele for negativo. */
    public Money atLeastZero() {
        return isNegative() ? ZERO : this;
    }

    public Money roundedToCents() {
        return new Money(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** Valor com duas casas decimais, para persistência e exibição. */
    public BigDecimal toCents() {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "R$ " + toCents().toPlainString();
    }
}
