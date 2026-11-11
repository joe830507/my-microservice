package com.mymicroservice.payment.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;
import com.mymicroservice.payment.dtos.PaymentResDto;
import com.mymicroservice.payment.service.PaymentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// payment-service 不驗 JWT，信任 Gateway 帶來的 X-User-Id、X-User-Roles
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    // 建立付款：只能付自己的訂單；新建立回 201，已經有進行中的付款回 200 和那一筆
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResDto> createPayment(@CurrentUserId UUID userId,
            @RequestBody @Valid CreatePaymentReqDto createPaymentReqDto) {
        CreatePaymentResult result = paymentService.createPayment(userId, createPaymentReqDto);
        if (!result.created()) {
            return ResponseEntity.status(HttpStatus.OK).body(result.payment());
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/" + result.payment().id()))
                .body(result.payment());
    }

    // 查付款狀態：本人或 ADMIN；別人的付款回 404
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public PaymentResDto getPayment(@CurrentUserId UUID userId,
            @RequestHeader(value = UserContextHeaders.USER_ROLES, required = false) String roles,
            @PathVariable UUID id) {
        return paymentService.getPayment(id, userId, isAdmin(roles));
    }

    private static boolean isAdmin(String roles) {
        return UserContext.parseRoles(roles).contains("ADMIN");
    }
}
