package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object monétaire immuable (décision R2, Money Model Option C
 * validée en Phase R : {@code BigDecimal} + enum {@code Currency}).
 *
 * <p><b>N'est jamais persisté tel quel</b> — les entités JPA
 * ({@link FinancialAccount}, {@link LedgerLine}) stockent {@code
 * BigDecimal}/{@code Currency} comme colonnes distinctes (cohérent avec le
 * reste du modèle existant, qui n'utilise aucun {@code @Embeddable}).
 * {@code Money} sert uniquement de garde-fou de validation/arithmétique
 * côté domaine et service, jamais côté persistance.</p>
 *
 * <p>Échelle fixée à 2 décimales, sans arrondi implicite : un montant
 * fourni avec plus de 2 décimales est rejeté explicitement ({@link
 * ArithmeticException} via {@link RoundingMode#UNNECESSARY}) plutôt
 * qu'arrondi silencieusement — aucune logique monétaire approximative.</p>
 */
public final class Money {

    private static final int SCALE = 2;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * @param amount   montant, non {@code null}, au plus 2 décimales significatives
     * @param currency devise, non {@code null}
     * @throws NullPointerException si {@code amount} ou {@code currency} est {@code null}
     * @throws ArithmeticException  si {@code amount} porte plus de 2 décimales (aucun arrondi implicite)
     */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount ne doit jamais être null");
        Objects.requireNonNull(currency, "currency ne doit jamais être null");
        return new Money(amount.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZeroOrPositive() {
        return amount.signum() >= 0;
    }

    /**
     * @throws IllegalArgumentException si {@code other} n'est pas dans la même devise —
     *                                  aucune opération arithmétique ne mélange deux devises
     */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    /**
     * @throws IllegalArgumentException si {@code other} n'est pas dans la même devise
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other ne doit jamais être null");
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "Devises incompatibles : " + this.currency + " et " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0 && currency == money.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
