package com.mymicroservice.inventory.service;

import java.util.UUID;

import com.mymicroservice.inventory.dtos.reservation.ReservationResDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveReqDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveResult;

public interface ReservationService {

    public ReserveResult reserve(ReserveReqDto reserveReqDto);

    public ReservationResDto confirm(UUID orderId);

    public ReservationResDto release(UUID orderId);
}
