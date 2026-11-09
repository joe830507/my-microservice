package com.mymicroservice.inventory.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.inventory.dtos.stock.StockResDto;
import com.mymicroservice.inventory.service.StockService;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

// 對外的庫存 API；inventory-service 不驗 JWT，信任 Gateway 帶來的 X-User-Id、X-User-Roles
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class StockController {
    private final StockService stockService;

    // 不需登入：商品頁要顯示庫存；Gateway 對 GET 放行
    // 長度上限和 DB 的 sku 欄位一致，超過的不可能存在，直接回 400
    @GetMapping(value = "/{sku}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public StockResDto getStock(@PathVariable @Size(max = 64) String sku) {
        return stockService.getStock(sku);
    }
}
