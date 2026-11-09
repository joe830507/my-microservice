package com.mymicroservice.order.service;

import java.util.UUID;

import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.GetOrdersReqDto;
import com.mymicroservice.order.dtos.GetOrdersResDto;
import com.mymicroservice.order.dtos.InternalOrderResDto;
import com.mymicroservice.order.dtos.OrderResDto;

public interface OrderService {

    public OrderResDto createOrder(UUID userId, CreateOrderReqDto createOrderReqDto);

    public OrderResDto getOrder(UUID orderId, UUID userId, boolean admin);

    public GetOrdersResDto getOrders(GetOrdersReqDto getOrdersReqDto, UUID userId, boolean admin);

    public OrderResDto cancelOrder(UUID orderId, UUID userId, boolean admin);

    public InternalOrderResDto getInternalOrder(UUID orderId);

    public InternalOrderResDto markPaid(UUID orderId);

    // 排程用：付款逾時自動取消；回傳是否真的取消（已付款或已取消的回 false）
    public boolean expireUnpaidOrder(UUID orderId);

    // 排程用：重新呼叫 inventory，讓預扣變成訂單記錄的狀態；回傳是否同步成功
    public boolean retryReservationSync(UUID orderId);
}
