package com.mymicroservice.inventory.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.inventory.dtos.stock.StockResDto;
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
}
