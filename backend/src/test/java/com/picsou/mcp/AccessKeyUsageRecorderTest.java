package com.picsou.mcp;

import com.picsou.repository.AccessKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccessKeyUsageRecorderTest {

    @Mock AccessKeyRepository repository;
    @InjectMocks AccessKeyUsageRecorder recorder;

    @Test
    void touch_delegatesToRepositoryBulkUpdate() {
        Instant ts = Instant.parse("2026-06-04T12:00:00Z");
        recorder.touch(5L, ts);
        verify(repository).touchLastUsedAt(5L, ts);
    }
}
