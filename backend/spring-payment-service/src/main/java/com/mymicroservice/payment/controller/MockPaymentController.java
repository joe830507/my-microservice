package com.mymicroservice.payment.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.CurrentUserId;
import com.mymicroservice.common.web.UserContext;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.dtos.MockCallbackReqDto;
import com.mymicroservice.payment.dtos.PaymentResDto;
import com.mymicroservice.payment.service.PaymentService;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 模擬第三方金流回呼：付款的本人（或 ADMIN）自己指定付款成功或失敗。
// 這等於讓顧客自己決定「我付過錢了」，所以**預設不存在**，只有明確設定 payment.mock-callback.enabled=true
// （環境變數 PAYMENT_MOCK_CALLBACK_ENABLED）才會註冊這支 API；沒開的時候這個路徑回 404（SEC-01）。
// 只該在本機開發、E2E 與示範環境開啟；真實金流的回呼由第三方伺服器呼叫並驗證簽章，到時整個類別移除
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.mock-callback", name = "enabled", havingValue = "true")
public class MockPaymentController {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentController.class);

    private final PaymentService paymentService;

    // 每次啟動都在日誌留下警告，避免不小心把它帶進正式環境卻沒人發現
    @PostConstruct
    void warnEnabled() {
        log.warn("payment.mock-callback.enabled=true：顧客可以自己把付款標成成功，只能用在開發、測試與示範環境");
    }

    @PostMapping(value = "/{id}/mock-callback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public PaymentResDto mockCallback(@CurrentUserId UUID userId,
            @RequestHeader(value = UserContextHeaders.USER_ROLES, required = false) String roles,
            @PathVariable UUID id, @RequestBody @Valid MockCallbackReqDto mockCallbackReqDto) {
        boolean admin = UserContext.parseRoles(roles).contains("ADMIN");
        return paymentService.mockCallback(id, userId, admin, mockCallbackReqDto);
    }
}
