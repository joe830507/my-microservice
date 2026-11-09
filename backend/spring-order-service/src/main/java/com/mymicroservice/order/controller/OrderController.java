package com.mymicroservice.order.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.CurrentUserId;
import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.OrderResDto;
import com.mymicroservice.order.service.OrderService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// order-service 不驗 JWT，信任 Gateway 帶來的 X-User-Id、X-User-Roles
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    // 建立訂單：查商品快照、預扣庫存、存成 PENDING_PAYMENT；回 201 與 Location
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResDto> createOrder(@CurrentUserId UUID userId,
            @RequestBody @Valid CreateOrderReqDto createOrderReqDto) {
        OrderResDto order = orderService.createOrder(userId, createOrderReqDto);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id())).body(order);
    }
}
