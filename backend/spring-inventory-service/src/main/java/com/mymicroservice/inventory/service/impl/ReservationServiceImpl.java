package com.mymicroservice.inventory.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.CommonAutoConfiguration;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.inventory.dtos.reservation.ReservationResDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveReqDto;
import com.mymicroservice.inventory.dtos.reservation.ReserveResult;
import com.mymicroservice.inventory.entities.Reservation;
import com.mymicroservice.inventory.entities.ReservationItem;
import com.mymicroservice.inventory.entities.Stock;
import com.mymicroservice.inventory.enums.ReservationStatus;
import com.mymicroservice.inventory.exception.InventoryErrorCode;
import com.mymicroservice.inventory.repositories.ReservationRepository;
import com.mymicroservice.inventory.repositories.StockRepository;
import com.mymicroservice.inventory.service.ReservationService;

@Service
@Transactional
public class ReservationServiceImpl implements ReservationService {
    private final ReservationRepository reservationRepository;
    private final StockRepository stockRepository;
    private final AuditorAware<UUID> auditorAware;

    public ReservationServiceImpl(ReservationRepository reservationRepository, StockRepository stockRepository,
            @Qualifier(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME) AuditorAware<UUID> auditorAware) {
        this.reservationRepository = reservationRepository;
        this.stockRepository = stockRepository;
        this.auditorAware = auditorAware;
    }

    // 全有全無：任何一個 SKU 失敗就丟例外，整個交易 rollback，已經扣的也會還原
    // 冪等：同一個 orderId 再送一次，內容相同就回原本的結果，不重複扣
    // 兩個相同 orderId 的請求同時送達：後到的在寫入 reservation 時被主鍵擋下 → 409 DATA_CONFLICT，
    // 它扣的庫存一起 rollback；呼叫方重試時就會走冪等的路徑
    @Override
    public ReserveResult reserve(ReserveReqDto reserveReqDto) {
        UUID orderId = reserveReqDto.orderId();
        SortedMap<String, Integer> requested = mergeBySku(reserveReqDto.items());

        Optional<Reservation> existing = reservationRepository.findWithItemsByOrderId(orderId);
        if (existing.isPresent()) {
            return replay(existing.get(), requested);
        }

        // 依 SKU 排序後更新：兩張訂單同時扣同一組 SKU 時，鎖 row 的順序一致，不會死結
        // 失敗了也繼續檢查剩下的，才能一次列出所有出問題的 SKU（反正最後會整筆 rollback）
        Instant now = Instant.now();
        UUID updatedBy = auditorAware.getCurrentAuditor().orElseThrow();
        List<String> failedSkus = new ArrayList<>();
        requested.forEach((sku, qty) -> {
            if (stockRepository.reserve(sku, qty, now, updatedBy) == 0) {
                failedSkus.add(sku);
            }
        });
        if (!failedSkus.isEmpty()) {
            throw toReserveFailure(failedSkus);
        }

        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setStatus(ReservationStatus.RESERVED);
        requested.forEach(reservation::addItem);
        // saveAndFlush：主鍵衝突在這裡就轉成 DataIntegrityViolationException，而不是到 commit 才失敗
        reservationRepository.saveAndFlush(reservation);
        return new ReserveResult(true, ReservationResDto.from(reservation));
    }

    // 付款成功後由 order-service 呼叫：RESERVED → CONFIRMED，並把預扣數量從 reserved 扣掉
    // 已經是 CONFIRMED → 不做任何事，回原本的結果（冪等）
    @Override
    public ReservationResDto confirm(UUID orderId) {
        Instant now = Instant.now();
        UUID updatedBy = auditorAware.getCurrentAuditor().orElseThrow();
        boolean transitioned = reservationRepository.transition(orderId, ReservationStatus.RESERVED,
                ReservationStatus.CONFIRMED, now, updatedBy) == 1;

        // 轉換之後才讀，讀到的是最新狀態；狀態轉換失敗時也要讀出來判斷原因
        Reservation reservation = reservationRepository.findWithItemsByOrderId(orderId)
                .orElseThrow(() -> new AppException(InventoryErrorCode.RESERVATION_NOT_FOUND, orderId));
        if (!transitioned) {
            if (reservation.getStatus() == ReservationStatus.RELEASED) {
                throw new AppException(InventoryErrorCode.RESERVATION_ALREADY_RELEASED, orderId);
            }
            return ReservationResDto.from(reservation);
        }

        // 明細已依 SKU 排序，鎖 row 的順序和預扣一致
        for (ReservationItem item : reservation.getItems()) {
            if (stockRepository.confirmReserved(item.getSku(), item.getQty(), now, updatedBy) == 0) {
                // reserved 只會被預扣、確認、釋放改變，正常不會發生；丟例外讓整筆 rollback，不留下半套結果
                throw new IllegalStateException(
                        "reserved quantity of " + item.getSku() + " is less than reservation of order " + orderId);
            }
        }
        return ReservationResDto.from(reservation);
    }

    // 已經有同一個 orderId 的紀錄
    private ReserveResult replay(Reservation reservation, SortedMap<String, Integer> requested) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new AppException(InventoryErrorCode.RESERVATION_ALREADY_RELEASED, reservation.getOrderId());
        }
        Map<String, Integer> reserved = reservation.getItems().stream()
                .collect(Collectors.toMap(ReservationItem::getSku, ReservationItem::getQty));
        if (!reserved.equals(requested)) {
            throw new AppException(InventoryErrorCode.RESERVATION_MISMATCH, reservation.getOrderId());
        }
        // RESERVED 或 CONFIRMED（已付款後才重送）都回原本的結果
        return new ReserveResult(false, ReservationResDto.from(reservation));
    }

    // 有 SKU 不存在 → 404 並列出不存在的；都存在 → 庫存不足，列出不足的
    private AppException toReserveFailure(List<String> failedSkus) {
        Set<String> existingSkus = stockRepository.findAllById(failedSkus).stream()
                .map(Stock::getSku)
                .collect(Collectors.toSet());
        List<String> missingSkus = failedSkus.stream().filter(sku -> !existingSkus.contains(sku)).toList();
        if (!missingSkus.isEmpty()) {
            return new AppException(InventoryErrorCode.STOCK_NOT_FOUND, String.join(", ", missingSkus));
        }
        return new AppException(InventoryErrorCode.INSUFFICIENT_STOCK, String.join(", ", failedSkus));
    }

    // 合併重複的 SKU，並依 SKU 排序
    private SortedMap<String, Integer> mergeBySku(List<ReserveReqDto.Item> items) {
        SortedMap<String, Integer> merged = new TreeMap<>();
        items.forEach(item -> merged.merge(item.sku(), item.qty(), Integer::sum));
        return merged;
    }
}
