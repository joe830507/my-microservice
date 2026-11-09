package com.mymicroservice.inventory.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.RequireRoles;
import com.mymicroservice.inventory.dtos.stock.SetStockReqDto;
import com.mymicroservice.inventory.dtos.stock.SetStockResult;
import com.mymicroservice.inventory.dtos.stock.StockResDto;
import com.mymicroservice.inventory.service.StockService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

// 對外的庫存 API；inventory-service 不驗 JWT，信任 Gateway 帶來的 X-User-Id、X-User-Roles
// sku 的長度上限和 DB 欄位、product-service 一致
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class StockController {
    private final StockService stockService;

    // 不需登入：商品頁要顯示庫存；Gateway 對 GET 放行
    @GetMapping(value = "/{sku}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public StockResDto getStock(@PathVariable @Size(max = 64) String sku) {
        return stockService.getStock(sku);
    }

    // 後台設定庫存，限 ADMIN；不存在就建立（201），存在就更新（200）
    // 權限在參數驗證之前檢查，非 ADMIN 一律先收到 401／403
    // 雛型階段不檢查 product-service 是否有這個 SKU
    @RequireRoles("ADMIN")
    @PutMapping(value = "/{sku}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StockResDto> setStock(@PathVariable @NotBlank @Size(max = 64) String sku,
            @RequestBody @Valid SetStockReqDto setStockReqDto) {
        SetStockResult result = stockService.setStock(sku, setStockReqDto);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.stock());
    }
}
