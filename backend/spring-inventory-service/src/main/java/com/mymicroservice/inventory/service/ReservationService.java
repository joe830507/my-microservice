package com.mymicroservice.inventory.service;

import com.mymicroservice.inventory.dtos.reservation.ReserveReqDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveResult;

public interface ReservationService {

    public ReserveResult reserve(ReserveReqDto reserveReqDto);
}
