package com.tontiflow.domain.enums;

/**
 * Devise supportée par le Ledger financier (décision R2, Option C validée
 * en Phase R : {@code BigDecimal} + enum {@code Currency}).
 *
 * <p><b>Une seule devise est supportée pour R2</b> : {@code MRU} (ouguiya
 * mauritanien, code ISO 4217). Aucune autre devise n'est ajoutée
 * prématurément — l'extension multi-devise reste une décision future
 * séparée.</p>
 *
 * <p>Mappé {@code @Enumerated(EnumType.STRING)} partout où il est persisté
 * (colonne {@code VARCHAR}) — règle R2 explicite : ne jamais reproduire
 * implicitement le mapping ORDINAL déjà rencontré sur {@code RoundStatus}/
 * {@code RotationType}/{@code NonCompliantBehavior} (tontine-service).</p>
 */
public enum Currency {
    MRU
}
