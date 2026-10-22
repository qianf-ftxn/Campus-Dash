package com.campusdash.application.usecase;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublishInputValidationTest {
    @Test void invalid_orders_are_rejected_before_any_wallet_or_cache_access() {
        var useCase = new PublishErrandUseCase(null,null,null,null,null);
        for(var cmd : new PublishErrandUseCase.Command[]{
            new PublishErrandUseCase.Command(1,1001,ErrandType.DELIVERY,"  ",100,1),
            new PublishErrandUseCase.Command(1,1001,ErrandType.DELIVERY,"a".repeat(65),100,1),
            new PublishErrandUseCase.Command(1,1001,ErrandType.DELIVERY,"valid",0,1),
            new PublishErrandUseCase.Command(1,1001,ErrandType.DELIVERY,"valid",10_000_001,1),
            new PublishErrandUseCase.Command(1,1001,ErrandType.DELIVERY,"valid",100,2),
            new PublishErrandUseCase.Command(0,1001,ErrandType.DELIVERY,"valid",100,1),
            new PublishErrandUseCase.Command(1,1001,null,"valid",100,1)}) {
            assertEquals(ErrorCode.INVALID_ARGUMENT,assertThrows(BizException.class,()->useCase.publish(cmd)).code());
        }
    }
}
