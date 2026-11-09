package com.mymicroservice.order.service;

import java.util.UUID;

import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.OrderResDto;

public interface OrderService {

    public OrderResDto createOrder(UUID userId, CreateOrderReqDto createOrderReqDto);
}
