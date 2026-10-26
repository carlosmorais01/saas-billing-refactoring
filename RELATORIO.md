# Relatório Técnico: Refatoração do SubscriptionBillingService

**Disciplina:** Implantação e Entrega de Software, 2026/2 (INF/UFG)
**Professora:** Sofia Costa Paiva
**Dupla:** Carlos Henrique Alves Morais e Vinícius Espíndola

## Como executar

Requisitos: Java 21 e Maven.

```bash
mvn test
```

A suíte roda sem banco de dados e sem servidor SMTP. Em produção, o serviço é montado por `BillingServiceFactory.fromEnvironment()`, que lê a configuração das variáveis de ambiente `BILLING_DB_URL`, `BILLING_DB_USER`, `BILLING_DB_PASSWORD`, `BILLING_SMTP_HOST`, `BILLING_SMTP_PORT` e `BILLING_MAIL_FROM`.

## 1. Diagnóstico do débito técnico

As linhas se referem ao código legado ([commit 1](https://github.com/carlosmorais01/saas-billing-refactoring/blob/01694f6/src/main/java/com/saas/billing/SubscriptionBillingService.java)).

| Linha/trecho do código afetado | Code Smell ou princípio violado | Juros decorrentes |
|---|---|---|
| Linhas 18-105 | Long Method | Dificuldade de entendimento e da manutenção do código. |
| Linhas 18-20 | Long Parameter List | Trocar a ordem de dois argumentos compila normalmente e gera cobrança errada sem nenhum aviso. Qualquer novo dado de cobrança altera a assinatura e quebra todos os chamadores. |
| Linhas 18, 24, 29-57 (`double basePrice`, `double calculatedPrice`, `String signupDateStr`) | Primitive Obsession | Double acumula erro de ponto flutuante e não tem regra de arredondamento. A data chega como String e só é validada em tempo de execução (`LocalDate.parse`). As regras de dinheiro ficam espalhadas no código ao invés de concentradas em um tipo `Money`. |
| Linha 18 (parâmetro `planType`) | Dead Code | Aumenta custo cognitivo e polui a assinatura do método. |
| Linhas 31-53 (`0.15`, `0.10`, `0.70`, `30.0`, `0.90`, `0.02`, `0.001`, `24`, `12`, `"BLACKFRIDAY"`, `"WELCOME10"`) | Magic Numbers | Mudar uma taxa exige procurar o número no meio do código e trocar todas as aparições. |
| Linhas 25, 27 e 42 (`LocalDate.now()`) | Dependência do relógio do sistema | O resultado muda conforme o dia em que o teste roda: o cupom BLACKFRIDAY só vale em novembro e `monthsActive` muda a cada mês. Os testes ficam não determinísticos (flaky) na CI, e cenários de data não podem ser reproduzidos. |
| Linhas 14-16 e 90-92 (credenciais do banco; host, porta e remetente do e-mail) | Credenciais e configuração hardcoded (segurança) | A senha do banco vai para o repositório e para o histórico do Git, o que é um risco de segurança. O mesmo vale para host, porta e remetente do SMTP. Trocar de ambiente (dev/homologação/produção) ou de servidor de e-mail exige alterar o código, recompilar e fazer novo deploy, impedindo o uso do mesmo artefato em toda a esteira. |
| Linhas 59-87 e 89-95 (`DriverManager`, SQL inline, `new SimpleEmail()`) | Dependência direta de I/O (banco e SMTP) / violação do Dependency Inversion Principle (DIP) | A regra de negócio depende de PostgreSQL. Trocar a persistência exige reescrever o serviço. O e-mail instancia `SimpleEmail` diretamente, então reativar o envio tornaria os testes dependentes de um servidor SMTP. Além disso, `PreparedStatement` e `ResultSet` não são fechados (vazamento de recursos). |
| Linhas 74-80 | Gateway de pagamento simulado inline (violação de SRP e DIP) | A "cobrança" é só um `startsWith("tok_")` dentro do serviço. Integrar um gateway real exigirá mexer no método central, com risco de regressão em precificação e faturamento, e não há como simular recusa ou timeout em testes. |
| Linhas 96 e 98 (`// emailSender.send();` e log "E-mail disparado") | Dead Code | O envio está comentado mesmo com log "E-mail disparado": o sistema mente sobre o próprio comportamento e esconde que nenhum recibo é enviado. |
| Linhas 12-105 (classe inteira) | Large Class | Uma única classe calcula preço, consulta cliente, cobra, persiste fatura e envia e-mail. Isso dificulta a manutenção e qualquer mudança (nova promoção, novo gateway, novo template de e-mail) mexe no mesmo arquivo, gera conflitos de merge entre equipes e aumenta o risco de cada deploy. |
| Linhas 100-102 | Tratamento genérico de exceção (`catch (Exception e)`) | Falhas de banco, de parse de data ou de e-mail viram apenas uma linha de log, e o chamador não consegue distinguir sucesso de erro. O pagamento pode ser aprovado sem que a fatura seja gravada. |
| Linhas 18 e 22 (retorno `List<String> logs`) | Uso de strings como resultado / mistura de log com retorno de negócio | O chamador precisa interpretar texto para saber se a cobrança deu certo, e qualquer alteração de mensagem quebra os consumidores e os testes. Os logs não vão para um mecanismo de observabilidade real. |

### Como cada problema foi tratado

| Problema | Tratamento | Commit |
|---|---|---|
| Long Method | `processMonthlyBilling` virou uma fachada de 12 linhas que delega a `PricingCalculator` e ao método privado `charge()`. | 4 |
| Long Parameter List | Os 8 parâmetros viraram o Parameter Object `BillingCommand`. | 2 |
| Primitive Obsession | Valores em `Money` (BigDecimal, imutável, arredondamento em um único ponto); data de adesão como `LocalDate`. | 2 |
| Dead Code (`planType`) | Parâmetro removido. | 2 |
| Magic Numbers | Viraram constantes nomeadas em `PricingCalculator`. | 4 |
| Relógio do sistema | `java.time.Clock` injetado no construtor; testes usam `Clock.fixed`. | 3 |
| Credenciais e configuração hardcoded | Lidas de variáveis de ambiente em `BillingServiceFactory`. | 3 |
| Dependência direta de I/O (banco e SMTP) | Portas `CustomerRepository`, `InvoiceRepository` e `NotificationService`, com adaptadores JDBC (try-with-resources) e SMTP. | 3 |
| Gateway de pagamento inline | Porta `PaymentGateway`; a regra do `"tok_"` ficou isolada em `TokenPaymentGateway`. | 3 |
| Dead Code (envio comentado) | O recibo passa a ser enviado de fato por `NotificationService`. | 3 |
| Large Class | Responsabilidades separadas em domínio (`domain`), portas (`port`) e adaptadores (`infrastructure`). | 3 e 4 |
| `catch (Exception e)` | Removido; falhas de infraestrutura são propagadas como `BillingInfrastructureException` com a causa original. | 3 |
| `List<String>` como resultado | **Mantido** para preservar o contrato público com os chamadores atuais. Fica como débito registrado para uma próxima iteração (trocar por um objeto de resultado). | |

## 2. Refatorações aplicadas

| Commit | Refatorações do catálogo | Equivalente no IntelliJ |
|---|---|---|
| 1. Código legado base | Nenhuma (código original organizado como projeto Maven) | |
| 2. Value Objects e remoção de Primitive Obsession | Introduce Parameter Object, Replace Primitive with Object, Remove Parameter | Refactor > Introduce Parameter Object; Change Signature (Ctrl+F6) |
| 3. Interfaces de infraestrutura e injeção de dependência | Extract Interface, Extract Class, Introduce Parameter (injeção pelo construtor), Replace Error Code with Exception | Refactor > Extract Interface; Refactor > Extract Delegate; Introduce Field (Ctrl+Alt+F) |
| 4. Motor de precificação e suíte verde | Extract Class, Extract Method, Replace Magic Number with Symbolic Constant, Decompose Conditional | Extract Method (Ctrl+Alt+M); Introduce Constant (Ctrl+Alt+C); Move Members (F6) |

### Estrutura final

| Pacote | Papel | Classes |
|---|---|---|
| `com.saas.billing` | Fachada (orquestração) | `SubscriptionBillingService` |
| `com.saas.billing.domain` | Regras puras, sem I/O | `Money`, `BillingCommand`, `Customer`, `Invoice`, `InvoiceStatus`, `PricingCalculator` |
| `com.saas.billing.port` | Interfaces dos serviços externos | `CustomerRepository`, `InvoiceRepository`, `PaymentGateway`, `NotificationService` |
| `com.saas.billing.infrastructure` | Adaptadores reais | `JdbcCustomerRepository`, `JdbcInvoiceRepository`, `TokenPaymentGateway`, `SmtpNotificationService`, `BillingServiceFactory` |

Além das três interfaces pedidas, foi criada `InvoiceRepository`, para não colocar a gravação de faturas dentro do repositório de clientes (Interface Segregation).

## 3. Métricas: antes vs. depois

Complexidade ciclomática, linhas e parâmetros medidos com [lizard](https://github.com/terryyin/lizard); cobertura medida com JaCoCo 0.8.12.

| Métrica | Antes (commit 1) | Depois (commit 4) |
|---|---|---|
| Linhas do método `processMonthlyBilling` | 88 | 12 |
| Complexidade ciclomática de `processMonthlyBilling` | 18 | 3 |
| Maior complexidade ciclomática entre os métodos do projeto | 18 | 6 (`PricingCalculator.applyPromoCode`) |
| Parâmetros de `processMonthlyBilling` | 8 | 1 (`BillingCommand`) |
| Classes/interfaces de produção | 1 | 18 |
| Imports de infraestrutura no serviço (`java.sql`, `commons-mail`) | 5 | 0 |
| Credenciais e endereços hardcoded | 6 (usuário, senha, URL, host, porta, remetente) | 0 |
| Literais de regra de negócio no serviço | 11 | 0 (constantes nomeadas no `PricingCalculator`) |
| Blocos `catch (Exception e)` | 1 | 0 |
| Testes automatizados | 0 | 39 |
| Dependências externas para rodar os testes | PostgreSQL e SMTP (código não testável) | Nenhuma |
| Cobertura de linhas, serviço (`com.saas.billing`) | 0% | 100% |
| Cobertura de linhas, domínio (`domain`) | | 97% (branches: 100%) |
| Cobertura de linhas, infraestrutura (`infrastructure`) | | 7% |

A cobertura baixa da infraestrutura é esperada: os adaptadores JDBC e SMTP só podem ser exercitados com banco e servidor reais, o que fica para testes de integração fora da esteira de testes unitários.

## 4. Preservação de comportamento

Como o código legado não podia ser testado (dependia de PostgreSQL e de `LocalDate.now()`), a validação foi feita em três camadas:

1. **Golden master** (`PricingCalculatorTest.preservaComportamentoDaFormulaLegada`): uma cópia literal da fórmula de preço legada (em `double`) é comparada ao `PricingCalculator` em **7.680 combinações** de preço base, data de adesão, dias de atraso, cupom, ONG e data atual. A diferença máxima foi de **R$ 0,01**, em 56 casos, todos causados pelo erro de ponto flutuante do legado. Exemplo: o valor exato 52,395 deveria ser arredondado para 52,40, mas o `double` armazena 52,394999999999996.
2. **Testes de regra** (20 cenários parametrizados): cada desconto, os limites de 12 e 24 meses, BLACKFRIDAY fora de novembro, cupom sem diferenciar maiúsculas, piso em zero e arredondamento.
3. **Testes do serviço** com fakes em memória e relógio fixo: cliente ativo, inativo e inexistente, falha de pagamento (a fatura é gravada como `FAILED` e o recibo continua sendo enviado, como no legado) e propagação de falhas de infraestrutura.

### Mudanças de comportamento intencionais

| Antes | Depois | Motivo |
|---|---|---|
| Valores sem arredondamento (`R$ 85.0`, `R$ 76.49999999`) | Arredondados para centavos (`R$ 85.00`) | Item 2.1 do enunciado (`Money` encapsula arredondamentos). |
| Recibo nunca era enviado, mas o log dizia "E-mail disparado" | Recibo enviado de fato | Remoção de código morto; o log passou a ser verdadeiro. |
| Falha de infraestrutura virava a linha de log "Erro fatal no processamento" | Exceção `BillingInfrastructureException` com a causa original | Item 2.4 do enunciado (remover `catch` genérico). |
| Parâmetro `planType` aceito e ignorado | Removido do `BillingCommand` | Código morto. |
| Data de adesão como String, validada no meio do processamento | `LocalDate`, validada na criação do comando | Primitive Obsession. |
