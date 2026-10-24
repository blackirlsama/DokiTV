package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanmakuAnalysisRangeTest {
    @Test void invalidOrUnboundedRangesAreRejectedBeforeReadingAnyDatabaseRows() {
        DataSource source=mock(DataSource.class);
        var service=new BusinessDanmakuAnalysis(new JdbcTemplate(source),new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",2));
        var principal=new BusinessAuthentication.Principal("tianmu","881");
        LocalDateTime start=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).atStartOfDay();
        for(LocalDateTime end:List.of(start,start.minusHours(1),start.plusHours(25),start.plusDays(3)))
            assertEquals(400,assertThrows(BridgeException.class,()->service.query(principal,1001,start,end)).status());
        assertThrows(BridgeException.class,()->service.query(principal,0,start,start.plusHours(1)));
        assertThrows(BridgeException.class,()->service.query(principal,1001,start.minusDays(40),start.minusDays(40).plusHours(1)));
        verifyNoInteractions(source);
    }
}
