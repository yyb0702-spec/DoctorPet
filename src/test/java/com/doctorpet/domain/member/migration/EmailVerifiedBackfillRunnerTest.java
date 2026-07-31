package com.doctorpet.domain.member.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 마커(schema_migrations) 존재 여부에 따라 백필을 건너뛰는지·실행 후 마커를 남기는지만
 * 검증한다(리뷰 지적 P1 대응). 실제 UPDATE가 기존 회원에게 정확히 적용되는지는 실제 MySQL이
 * 필요해 {@link EmailVerifiedBackfillRunnerIntegrationTest}(Level 3)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerifiedBackfillRunnerTest {

    @Mock
    private SchemaMigrationRepository schemaMigrationRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @Test
    void 마커가_이미_있으면_백필을_다시_실행하지_않는다() throws Exception {
        given(schemaMigrationRepository.existsById("email_verified_backfill_v1")).willReturn(true);
        EmailVerifiedBackfillRunner runner =
                new EmailVerifiedBackfillRunner(schemaMigrationRepository, entityManager);

        runner.run(null);

        verify(entityManager, never()).createNativeQuery(anyString());
        verify(schemaMigrationRepository, never()).save(any());
    }

    @Test
    void 마커가_없으면_백필을_실행하고_마커를_남긴다() throws Exception {
        given(schemaMigrationRepository.existsById("email_verified_backfill_v1")).willReturn(false);
        given(entityManager.createNativeQuery(anyString())).willReturn(query);
        given(query.executeUpdate()).willReturn(3);
        EmailVerifiedBackfillRunner runner =
                new EmailVerifiedBackfillRunner(schemaMigrationRepository, entityManager);

        runner.run(null);

        verify(query).executeUpdate();
        ArgumentCaptor<SchemaMigrationRecord> captor = ArgumentCaptor.forClass(SchemaMigrationRecord.class);
        verify(schemaMigrationRepository).save(captor.capture());
        assertThat(captor.getValue().getMigrationKey()).isEqualTo("email_verified_backfill_v1");
    }
}
