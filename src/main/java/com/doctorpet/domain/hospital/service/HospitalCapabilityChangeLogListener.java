package com.doctorpet.domain.hospital.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class HospitalCapabilityChangeLogListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void logAfterCommit(HospitalCapabilitiesChangedEvent event) {
        log.info(
                "hospital_capabilities_updated hospitalId={} actorMemberId={} beforeCount={} afterCount={}",
                event.hospitalId(),
                event.actorMemberId(),
                event.beforeCount(),
                event.afterCount()
        );
    }
}
