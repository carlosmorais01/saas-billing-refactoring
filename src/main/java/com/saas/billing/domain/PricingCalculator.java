package com.saas.billing.domain;

import java.time.LocalDate;
import java.time.Month;

/**
 * Regras de precificação da assinatura mensal. Classe de domínio pura:
 * sem I/O e sem relógio próprio (a data de referência é recebida como parâmetro).
 *
 * A ordem de aplicação é a do sistema legado: fidelidade, ONG, cupom,
 * multa/juros por atraso e, por fim, piso em zero.
 */
public class PricingCalculator {

    static final int TWO_YEARS_LOYALTY_MONTHS = 24;
    static final int ONE_YEAR_LOYALTY_MONTHS = 12;
    static final String TWO_YEARS_LOYALTY_DISCOUNT = "0.15";
    static final String ONE_YEAR_LOYALTY_DISCOUNT = "0.10";

    static final String NON_PROFIT_PRICE_FACTOR = "0.70";

    static final String BLACK_FRIDAY_CODE = "BLACKFRIDAY";
    static final Month BLACK_FRIDAY_MONTH = Month.NOVEMBER;
    static final Money BLACK_FRIDAY_DISCOUNT = Money.of("30.00");
    static final String WELCOME_CODE = "WELCOME10";
    static final int WELCOME_MAX_MONTHS_ACTIVE = 1;
    static final String WELCOME_PRICE_FACTOR = "0.90";

    static final String LATE_PENALTY_RATE = "0.02";
    static final String LATE_DAILY_INTEREST_RATE = "0.001";

    public Money calculate(BillingCommand command, LocalDate today) {
        int monthsActive = monthsActive(command.signupDate(), today);

        Money price = applyLoyaltyDiscount(command.basePrice(), monthsActive);
        price = applyNonProfitDiscount(price, command.nonProfit());
        price = applyPromoCode(price, command, monthsActive, today);
        price = applyLateFees(price, command.overdueDays());

        return price.atLeastZero().roundedToCents();
    }

    /** Diferença em meses de calendário, ignorando o dia (mesma regra do legado). */
    int monthsActive(LocalDate signupDate, LocalDate today) {
        return (today.getYear() - signupDate.getYear()) * 12
                + (today.getMonthValue() - signupDate.getMonthValue());
    }

    private Money applyLoyaltyDiscount(Money basePrice, int monthsActive) {
        if (monthsActive > TWO_YEARS_LOYALTY_MONTHS) {
            return basePrice.minus(basePrice.times(TWO_YEARS_LOYALTY_DISCOUNT));
        }
        if (monthsActive > ONE_YEAR_LOYALTY_MONTHS) {
            return basePrice.minus(basePrice.times(ONE_YEAR_LOYALTY_DISCOUNT));
        }
        return basePrice;
    }

    private Money applyNonProfitDiscount(Money price, boolean nonProfit) {
        return nonProfit ? price.times(NON_PROFIT_PRICE_FACTOR) : price;
    }

    private Money applyPromoCode(Money price, BillingCommand command, int monthsActive, LocalDate today) {
        if (!command.hasPromoCode()) {
            return price;
        }
        String code = command.promoCode();
        if (code.equalsIgnoreCase(BLACK_FRIDAY_CODE) && today.getMonth() == BLACK_FRIDAY_MONTH) {
            return price.minus(BLACK_FRIDAY_DISCOUNT);
        }
        if (code.equalsIgnoreCase(WELCOME_CODE) && monthsActive <= WELCOME_MAX_MONTHS_ACTIVE) {
            return price.times(WELCOME_PRICE_FACTOR);
        }
        return price;
    }

    private Money applyLateFees(Money price, int overdueDays) {
        if (overdueDays <= 0) {
            return price;
        }
        Money penalty = price.times(LATE_PENALTY_RATE);
        Money interest = price.times(LATE_DAILY_INTEREST_RATE).times(overdueDays);
        return price.plus(penalty).plus(interest);
    }
}
