package com.mymicroservice.order.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.order.dtos.InternalOrderResDto;
import com.mymicroservice.order.service.OrderService;

import lombok.RequiredArgsConstructor;

// 內部 API，只供其他服務（payment-service）呼叫：Gateway 不轉發 /internal/**，之後再加 K8s NetworkPolicy 限制來源
// 沒有使用者身分，不檢查訂單擁有者
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class InternalOrderController {
    private final OrderService orderService;

    // payment-service 建立付款前查金額與狀態
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public InternalOrderResDto getOrder(@PathVariable UUID id) {
        return orderService.getInternalOrder(id);
    }
}
