package com.enterpriseos;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SetupControllerTest {
    @Test void disabledSetupDoesNotReadOrCreateAccounts(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        SetupController controller=new SetupController(jdbc,new BCryptPasswordEncoder(),mock(AuditService.class),false);
        assertEquals(false,controller.status().get("required"));
        assertThrows(ApiException.class,()->controller.initialize(Map.of()));
        verifyNoInteractions(jdbc);
    }
    @Test void existingInstallationCannotBeReinitialized(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).thenReturn(2L);
        SetupController controller=new SetupController(jdbc,new BCryptPasswordEncoder(),mock(AuditService.class),true);
        assertEquals(false,controller.status().get("required"));
        assertThrows(ApiException.class,()->controller.initialize(Map.of()));
        verify(jdbc).queryForList("SELECT id FROM setup_guard WHERE id=1 FOR UPDATE");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
