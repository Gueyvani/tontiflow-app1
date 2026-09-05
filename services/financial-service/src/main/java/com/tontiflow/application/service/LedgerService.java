package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountStatus;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import com.tontiflow.domain.model.LedgerLine;
import com.tontiflow.infrastructure.repository.FinancialAccountRepository;
import com.tontiflow.infrastructure.repository.JournalEntryRepository;
import com.tontiflow.infrastructure.repository.LedgerLineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cœur du Ledger financier (décision R2) : seul point d'entrée autorisé à
 * créer des {@link FinancialAccount}, {@link JournalEntry} et {@link
 * LedgerLine}. Garantit l'invariant comptable absolu (§8, Phase R2) :
 * {@code Σdebit = Σcredit} pour chaque écriture, impossible à violer en
 * persistance.
 *
 * <p><b>Bug réel découvert et corrigé pendant R2</b> (preuve : {@code
 * LedgerConcurrencyIntegrationTest}, échec initial reproductible) : capturer
 * {@link DataIntegrityViolationException} puis réutiliser le <i>même</i>
 * {@code EntityManager}/transaction pour une requête de secours échoue —
 * Hibernate retente un auto-flush de l'action d'insertion déjà en échec
 * avant d'exécuter la requête suivante, relançant la même exception. La
 * tentative d'écriture et la relecture de secours doivent donc s'exécuter
 * dans deux transactions strictement séparées — même patron {@code self}/
 * {@code @Lazy} que {@code RoundCompletionScheduler} (tontine-service),
 * nécessaire ici pour que l'appel passe réellement par le proxy Spring et
 * ouvre une transaction distincte.</p>
 */
@Service
public class LedgerService {

    private final FinancialAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerLineRepository ledgerLineRepository;
    private final Clock clock;

    /** Cf. Javadoc de classe — nécessaire pour forcer deux transactions distinctes. */
    private LedgerService self;

    public LedgerService(FinancialAccountRepository accountRepository,
                          JournalEntryRepository journalEntryRepository,
                          LedgerLineRepository ledgerLineRepository,
                          Clock clock) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerLineRepository = ledgerLineRepository;
        this.clock = clock;
    }

    @Autowired
    public void setSelf(@Lazy LedgerService self) {
        this.self = self;
    }

    /**
     * Retourne le compte financier existant pour {@code (ownerReference,
     * accountType)}, ou le crée s'il n'existe pas encore — idempotent y
     * compris sous création concurrente : la contrainte {@code UNIQUE}
     * {@code uk_financial_account_owner_type} (V1) est la garantie finale,
     * la pré-vérification n'étant qu'une optimisation.
     */
    public FinancialAccount getOrCreateAccount(Long ownerReference, FinancialAccountType accountType, Currency currency) {
        Objects.requireNonNull(ownerReference, "ownerReference ne doit jamais être null");
        Objects.requireNonNull(accountType, "accountType ne doit jamais être null");
        Objects.requireNonNull(currency, "currency ne doit jamais être null");

        try {
            return self.createAccount(ownerReference, accountType, currency);
        } catch (DataIntegrityViolationException e) {
            // Creation concurrente : un autre appelant a gagne la course sur la
            // contrainte UNIQUE (ownerReference, accountType) - nouvelle
            // transaction (self, cf. Javadoc de classe) pour retrouver le
            // compte qu'il vient de creer.
            return self.findAccountOrThrow(ownerReference, accountType, e);
        }
    }

    @Transactional
    FinancialAccount createAccount(Long ownerReference, FinancialAccountType accountType, Currency currency) {
        return accountRepository.findByOwnerReferenceAndAccountType(ownerReference, accountType)
                .orElseGet(() -> {
                    FinancialAccount account = new FinancialAccount();
                    account.setOwnerReference(ownerReference);
                    account.setAccountType(accountType);
                    account.setCurrency(currency);
                    account.setStatus(FinancialAccountStatus.ACTIVE);
                    account.setCreatedAt(clock.instant());
                    return accountRepository.saveAndFlush(account);
                });
    }

    @Transactional(readOnly = true)
    FinancialAccount findAccountOrThrow(Long ownerReference, FinancialAccountType accountType,
                                         DataIntegrityViolationException original) {
        return accountRepository.findByOwnerReferenceAndAccountType(ownerReference, accountType)
                .orElseThrow(() -> original);
    }

    /**
     * Comptabilise une écriture équilibrée (décision R2, §8/§9) : persiste
     * une {@link JournalEntry} et ses {@link LedgerLine} dans une unique
     * transaction — soit toutes les lignes sont écrites, soit aucune.
     *
     * <p>Idempotent sur {@code idempotencyKey} (§12) : un second appel avec
     * la même clé, y compris concurrent, ne crée jamais une seconde
     * écriture — retourne l'écriture déjà existante.</p>
     *
     * @throws IllegalArgumentException si l'écriture n'est pas équilibrée (Σdebit ≠ Σcredit),
     *                                  si une ligne mélange débit et crédit, si moins de 2 lignes
     *                                  sont fournies, ou si un compte référencé est introuvable
     */
    public JournalEntry record(String businessReference, String eventType, String idempotencyKey,
                                String description, Currency currency, List<PostingLine> lines) {
        Objects.requireNonNull(businessReference, "businessReference ne doit jamais être null");
        Objects.requireNonNull(eventType, "eventType ne doit jamais être null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey ne doit jamais être null");
        Objects.requireNonNull(currency, "currency ne doit jamais être null");
        Objects.requireNonNull(lines, "lines ne doit jamais être null");

        if (lines.size() < 2) {
            throw new IllegalArgumentException("Une écriture comptable nécessite au moins 2 lignes");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (PostingLine line : lines) {
            validateLine(line);
            totalDebit = totalDebit.add(line.debit());
            totalCredit = totalCredit.add(line.credit());
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                    "Écriture déséquilibrée : débit total " + totalDebit + " ≠ crédit total " + totalCredit);
        }

        try {
            return self.persistEntry(businessReference, eventType, idempotencyKey, description, currency, lines);
        } catch (DataIntegrityViolationException e) {
            // idempotencyKey deja utilisee (course concurrente ou nouvel appel
            // du meme appelant) : nouvelle transaction (self, cf. Javadoc de
            // classe) pour retrouver l'ecriture existante plutot que de
            // propager une erreur de contrainte brute - aucune double
            // comptabilisation, aucune duplication.
            return self.findEntryOrThrow(idempotencyKey, e);
        }
    }

    @Transactional
    JournalEntry persistEntry(String businessReference, String eventType, String idempotencyKey,
                               String description, Currency currency, List<PostingLine> lines) {
        JournalEntry entry = new JournalEntry();
        entry.setBusinessReference(businessReference);
        entry.setEventType(eventType);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setDescription(description);
        entry.setCreatedAt(clock.instant());

        JournalEntry saved = journalEntryRepository.saveAndFlush(entry);
        for (PostingLine line : lines) {
            FinancialAccount account = accountRepository.findById(line.financialAccountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Compte financier introuvable : " + line.financialAccountId()));
            LedgerLine ledgerLine = new LedgerLine();
            ledgerLine.setJournalEntry(saved);
            ledgerLine.setFinancialAccount(account);
            ledgerLine.setDebit(line.debit());
            ledgerLine.setCredit(line.credit());
            ledgerLine.setCurrency(currency);
            ledgerLineRepository.save(ledgerLine);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    JournalEntry findEntryOrThrow(String idempotencyKey, DataIntegrityViolationException original) {
        return journalEntryRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> original);
    }

    /** Solde dérivé du Ledger (§10) — jamais un champ mis en cache. */
    public BigDecimal computeBalance(UUID financialAccountId) {
        return ledgerLineRepository.computeBalance(financialAccountId);
    }

    /**
     * Solde d'un compte identifié par {@code (ownerReference, accountType)}
     * (décision R7) — jamais de création de compte pour une simple lecture :
     * si aucun {@link FinancialAccount} n'existe encore, le solde est
     * {@code 0.00} par construction (absence d'écriture = absence de
     * mouvement), conséquence directe de l'invariant "solde dérivé, jamais
     * mis en cache" (§10, décision R2) — pas une nouvelle règle métier.
     * {@code Currency.MRU} en absence de compte : seule devise supportée
     * (décision R2), pas une valeur inventée.
     *
     * <p>Réutilise {@link #computeBalance(UUID)} tel quel — la formule
     * {@code Σdebit - Σcredit} reste centralisée dans {@link
     * LedgerLineRepository#computeBalance}, jamais dupliquée ici.</p>
     */
    @Transactional(readOnly = true)
    public AccountBalance getAccountBalance(Long ownerReference, FinancialAccountType accountType) {
        return accountRepository.findByOwnerReferenceAndAccountType(ownerReference, accountType)
                .map(account -> new AccountBalance(
                        ownerReference, accountType, account.getCurrency(), computeBalance(account.getId())))
                .orElseGet(() -> new AccountBalance(
                        ownerReference, accountType, Currency.MRU, BigDecimal.ZERO.setScale(2)));
    }

    /**
     * Relevé d'un compte identifié par {@code (ownerReference, accountType)}
     * (décision R10) — détail des écritures qui composent le solde déjà
     * exposé par {@link #getAccountBalance} (décisions R7/R8). Réutilise
     * {@link LedgerLineRepository#findByFinancialAccount_Id} tel quel
     * (existait déjà, jamais lu — cf. rapport d'inspection R9). Compte
     * absent → liste vide, jamais de création (même principe que
     * {@link #getAccountBalance}). Mapping vers {@link LedgerLineDetail}
     * effectué à l'intérieur de cette transaction en lecture seule — accès à
     * {@code LedgerLine.getJournalEntry()} (chargement paresseux) avant
     * fermeture de la session, sans dépendre d'Open Session In View.
     */
    @Transactional(readOnly = true)
    public List<LedgerLineDetail> getAccountStatement(Long ownerReference, FinancialAccountType accountType) {
        return accountRepository.findByOwnerReferenceAndAccountType(ownerReference, accountType)
                .map(account -> ledgerLineRepository.findByFinancialAccount_Id(account.getId()).stream()
                        .map(line -> new LedgerLineDetail(
                                line.getJournalEntry().getEventType(),
                                line.getJournalEntry().getDescription(),
                                line.getDebit(),
                                line.getCredit(),
                                line.getCurrency(),
                                line.getJournalEntry().getCreatedAt()))
                        .toList())
                .orElseGet(List::of);
    }

    private static void validateLine(PostingLine line) {
        Objects.requireNonNull(line.financialAccountId(), "financialAccountId ne doit jamais être null");
        BigDecimal debit = Objects.requireNonNull(line.debit(), "debit ne doit jamais être null");
        BigDecimal credit = Objects.requireNonNull(line.credit(), "credit ne doit jamais être null");

        if (debit.signum() < 0 || credit.signum() < 0) {
            throw new IllegalArgumentException("debit et credit doivent être positifs ou nuls");
        }
        boolean isDebitLine = debit.signum() > 0 && credit.signum() == 0;
        boolean isCreditLine = credit.signum() > 0 && debit.signum() == 0;
        if (!isDebitLine && !isCreditLine) {
            throw new IllegalArgumentException(
                    "Une ligne doit être soit un débit, soit un crédit, jamais les deux ni aucun des deux "
                            + "(debit=" + debit + ", credit=" + credit + ")");
        }
    }
}
