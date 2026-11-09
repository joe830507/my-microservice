package com.mymicroservice.order.controller;

import java.net.URI;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
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
import com.mymicroservice.common.web.RequireRoles;
import com.mymicroservice.common.web.UserContext;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.GetOrdersReqDto;
import com.mymicroservice.order.dtos.GetOrdersResDto;
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

    // 訂單列表：一般使用者只看得到自己的；ADMIN 看全部，可用 userId 篩選
    // @ParameterObject 讓 springdoc 把 record 欄位展開成個別查詢參數
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public GetOrdersResDto getOrders(@CurrentUserId UUID userId,
            @RequestHeader(value = UserContextHeaders.USER_ROLES, required = false) String roles,
            @ParameterObject @Valid GetOrdersReqDto getOrdersReqDto) {
        return orderService.getOrders(getOrdersReqDto, userId, isAdmin(roles));
    }

    // 訂單詳情：本人或 ADMIN；別人的訂單回 404
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public OrderResDto getOrder(@CurrentUserId UUID userId,
            @RequestHeader(value = UserContextHeaders.USER_ROLES, required = false) String roles,
            @PathVariable UUID id) {
        return orderService.getOrder(id, userId, isAdmin(roles));
    }

    // 取消訂單：只限 PENDING_PAYMENT；已取消的回 200；本人或 ADMIN，別人的訂單回 404
    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public OrderResDto cancelOrder(@CurrentUserId UUID userId,
            @RequestHeader(value = UserContextHeaders.USER_ROLES, required = false) String roles,
            @PathVariable UUID id) {
        return orderService.cancelOrder(id, userId, isAdmin(roles));
    }

    // 後台出貨：只限 PAID；已經出貨的回 200；限 ADMIN
    @RequireRoles("ADMIN")
    @PostMapping(value = "/{id}/ship", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public OrderResDto shipOrder(@PathVariable UUID id) {
        return orderService.shipOrder(id);
    }

    // 後台完成訂單：只限 SHIPPED；已經完成的回 200；限 ADMIN
    @RequireRoles("ADMIN")
    @PostMapping(value = "/{id}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public OrderResDto completeOrder(@PathVariable UUID id) {
        return orderService.completeOrder(id);
    }

    private static boolean isAdmin(String roles) {
        return UserContext.parseRoles(roles).contains("ADMIN");
    }
}
