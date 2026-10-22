package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.consumer.RocketMQConsumer;
import com.orangecode.tianmu.service.BulletService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerRetryTest {
    @Test void storageFailureEscapesConsumerSoBrokerCanRetry() {
        BulletService service=mock(BulletService.class); doThrow(new IllegalStateException("database unavailable")).when(service).saveBulletToMySQL(any());
        var consumer=new RocketMQConsumer(); ReflectionTestUtils.setField(consumer,"bulletService",service);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage("{\"bulletId\":9001,\"videoId\":1001}"));
        verify(service,times(1)).saveBulletToMySQL(any());
    }
}
