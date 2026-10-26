package com.saas.billing.infrastructure;

import com.saas.billing.domain.Money;
import com.saas.billing.port.PaymentGateway;

/**
 * Gateway que preserva a regra do sistema legado: aprova qualquer token
 * com o prefixo "tok_". Deve ser substituído pelo adaptador do gateway real.
 */
public class TokenPaymentGateway implements PaymentGateway {

    private static final String VALID_TOKEN_PREFIX = "tok_";

    @Override
    public boolean charge(String cardToken, Money amount) {
        return cardToken != null && cardToken.startsWith(VALID_TOKEN_PREFIX);
    }
}
