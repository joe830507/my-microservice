package com.mymicroservice.inventory.service.impl;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.inventory.dtos.stock.SetStockReqDto;
import com.mymicroservice.inventory.dtos.stock.SetStockResult;
import com.mymicroservice.inventory.dtos.stock.StockResDto;
import com.mymicroservice.inventory.entities.Stock;
import com.mymicroservice.inventory.exception.InventoryErrorCode;
import com.mymicroservice.inventory.repositories.StockRepository;
import com.mymicroservice.inventory.service.StockService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {
    private final StockRepository stockRepository;

    @Override
    @Transactional(readOnly = true)
    public StockResDto getStock(String sku) {
        return stockRepository.findById(sku)
                .map(StockResDto::from)
                .orElseThrow(() -> new AppException(InventoryErrorCode.STOCK_NOT_FOUND, sku));
    }

    // 不存在就建立，存在就把 available 設成指定值
    // 讀出後到寫入前，預扣若剛好扣了這個 SKU，version 已經變了 → @Version 擋下回 409 VERSION_CONFLICT，
    // 請管理者重新讀取：直接覆蓋的話，剛被預扣的數量會被算回可賣數量，造成超賣
    // 兩個請求同時建立同一個 SKU：後到的被主鍵擋下，回 409 DATA_CONFLICT
    // 用 saveAndFlush：在這裡就送出 SQL，衝突會被轉成上面兩種例外，而不是到 commit 才失敗
    @Override
    public SetStockResult setStock(String sku, SetStockReqDto setStockReqDto) {
        Optional<Stock> existing = stockRepository.findById(sku);
        Stock stock = existing.orElseGet(() -> {
            Stock newStock = new Stock();
            newStock.setSku(sku);
            return newStock;
        });
        stock.setAvailable(setStockReqDto.available());
        Stock saved = stockRepository.saveAndFlush(stock);
        return new SetStockResult(existing.isEmpty(), StockResDto.from(saved));
    }
}
