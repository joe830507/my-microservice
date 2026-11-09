package com.mymicroservice.inventory.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.inventory.dtos.reservation.ReservationResDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveReqDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveResult;
import com.mymicroservice.inventory.service.ReservationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 內部 API，只供其他服務呼叫：Gateway 不轉發 /internal/**，之後再加 K8s NetworkPolicy 限制來源
// 沒有使用者身分，稽核欄位記為系統使用者
@RestController
@RequestMapping("/internal/v1/inventory/reservations")
@RequiredArgsConstructor
public class InternalReservationController {
    private final ReservationService reservationService;

    // 新建立回 201；同一個 orderId、相同內容重送回 200 和原本的結果
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationResDto> reserve(@RequestBody @Valid ReserveReqDto reserveReqDto) {
        ReserveResult result = reservationService.reserve(reserveReqDto);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.reservation());
    }

    // 付款成功後呼叫；重複確認回 200 和目前的結果
    @PostMapping(value = "/{orderId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public ReservationResDto confirm(@PathVariable UUID orderId) {
        return reservationService.confirm(orderId);
    }
}
