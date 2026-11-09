package com.mymicroservice.inventory.service;

import com.mymicroservice.inventory.dtos.stock.StockResDto;

public interface StockService {

    public StockResDto getStock(String sku);
}
