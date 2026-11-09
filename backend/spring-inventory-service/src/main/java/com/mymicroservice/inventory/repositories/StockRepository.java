package com.mymicroservice.inventory.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.inventory.entities.Stock;

public interface StockRepository extends JpaRepository<Stock, String> {
}
