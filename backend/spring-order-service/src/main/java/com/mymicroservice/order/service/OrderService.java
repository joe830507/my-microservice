package com.mymicroservice.order.service;

import java.util.UUID;

import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.CreateOrderResult;
import com.mymicroservice.order.dtos.GetOrdersReqDto;
import com.mymicroservice.order.dtos.GetOrdersResDto;
import com.mymicroservice.order.dtos.InternalOrderResDto;
import com.mymicroservice.order.dtos.OrderResDto;

public interface OrderService {

    // idempotencyKey：選填；同一個使用者用同一個 key 重送，回同一張訂單（CreateOrderResult.created = false）
    public CreateOrderResult createOrder(UUID userId, CreateOrderReqDto createOrderReqDto, String idempotencyKey);

    public OrderResDto getOrder(UUID orderId, UUID userId, boolean admin);

    public GetOrdersResDto getOrders(GetOrdersReqDto getOrdersReqDto, UUID userId, boolean admin);

    public OrderResDto cancelOrder(UUID orderId, UUID userId, boolean admin);

    // 後台出貨（ADMIN）：PAID → SHIPPED
    public OrderResDto shipOrder(UUID orderId);

    // 後台完成訂單（ADMIN）：SHIPPED → COMPLETED
    public OrderResDto completeOrder(UUID orderId);

    public InternalOrderResDto getInternalOrder(UUID orderId);

    public InternalOrderResDto markPaid(UUID orderId);

    // 排程用：付款逾時自動取消；回傳是否真的取消（已付款或已取消的回 false）
    public boolean expireUnpaidOrder(UUID orderId);

    // 排程用：重新呼叫 inventory，讓預扣變成訂單記錄的狀態；回傳是否同步成功
    public boolean retryReservationSync(UUID orderId);
}
