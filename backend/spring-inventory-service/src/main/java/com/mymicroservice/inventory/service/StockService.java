package com.mymicroservice.inventory.service;

import com.mymicroservice.inventory.dtos.stock.SetStockReqDto;
import com.mymicroservice.inventory.dtos.stock.SetStockResult;
import com.mymicroservice.inventory.dtos.stock.StockResDto;

public interface StockService {

    public StockResDto getStock(String sku);

    public SetStockResult setStock(String sku, SetStockReqDto setStockReqDto);
}
