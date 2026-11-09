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
}
