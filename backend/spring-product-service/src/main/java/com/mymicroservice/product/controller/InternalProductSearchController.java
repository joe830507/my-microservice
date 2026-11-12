package com.mymicroservice.product.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.product.search.ProductIndexRebuildService;

import lombok.RequiredArgsConstructor;

// 內部 API；Gateway 不建立 /internal/** 路由，後續由 NetworkPolicy 限制來源。
@RestController
@RequestMapping("/internal/v1/products/search-index")
@RequiredArgsConstructor
public class InternalProductSearchController {
    private final ProductIndexRebuildService rebuildService;

    @PostMapping(value = "/rebuild", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public ProductIndexRebuildService.RebuildResult rebuild(
            @RequestParam(defaultValue = "false") boolean deleteOld) {
        return rebuildService.rebuild(deleteOld);
    }
}
