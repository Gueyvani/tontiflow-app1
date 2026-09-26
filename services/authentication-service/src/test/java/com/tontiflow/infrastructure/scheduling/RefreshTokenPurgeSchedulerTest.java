package com.tontiflow.infrastructure.scheduling;

import com.tontiflow.application.service.RefreshTokenPurgeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Décision F-2 : la tâche planifiée n'existe (et la planification n'est activée) que si
 * {@code refresh-token.purge.enabled} est vrai (défaut) ; une erreur de purge n'interrompt pas la planification.
 */
class RefreshTokenPurgeSchedulerTest {

    private final RefreshTokenPurgeService purgeService = mock(RefreshTokenPurgeService.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(RefreshTokenPurgeService.class, () -> purgeService)
                .withUserConfiguration(RefreshTokenPurgeScheduler.class);
    }

    @Test
    void enabledByDefault_registersTheSchedulerAndEnablesScheduling() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(RefreshTokenPurgeScheduler.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void disabled_registersNeitherTheSchedulerNorTheSchedulingInfrastructure() {
        runner().withPropertyValues("refresh-token.purge.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RefreshTokenPurgeScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void purge_delegatesToTheService() {
        when(purgeService.purgeExpiredFamilies()).thenReturn(new RefreshTokenPurgeService.PurgeResult(3, 1));

        new RefreshTokenPurgeScheduler(purgeService).purge();

        verify(purgeService).purgeExpiredFamilies();
    }

    @Test
    void purgeFailure_isSwallowed_soThatTheNextScheduledPassStillRuns() {
        when(purgeService.purgeExpiredFamilies()).thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> new RefreshTokenPurgeScheduler(purgeService).purge()).doesNotThrowAnyException();
    }
}
