package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.application.service.LedgerLineDetail;
import com.tontiflow.domain.enums.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Réponse de relevé de compte (décision R10) : n'expose que des valeurs
 * dérivées, jamais {@code LedgerLine}/{@code JournalEntry} directement —
 * même discipline que {@code AccountBalanceResponse} (décision R7).
 */
public record LedgerLineResponse(String eventType, String description, BigDecimal debit, BigDecimal credit,
                                  Currency currency, Instant createdAt) {
    public static LedgerLineResponse from(LedgerLineDetail detail) {
        return new LedgerLineResponse(
                detail.eventType(), detail.description(), detail.debit(), detail.credit(),
                detail.currency(), detail.createdAt());
    }
}
