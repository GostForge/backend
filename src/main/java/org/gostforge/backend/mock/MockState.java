package org.gostforge.backend.mock;

import lombok.Data;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
@Data
public class MockState {
    private int globalDelayMs = 0;
    
    private int md2gostStatusCode = 200;
    private int md2gostDelayMs = 0;
    
    private int gotenbergStatusCode = 200;
    private int gotenbergDelayMs = 0;
    
    private int docx2mdStatusCode = 200;
    private int docx2mdDelayMs = 0;
    
}
