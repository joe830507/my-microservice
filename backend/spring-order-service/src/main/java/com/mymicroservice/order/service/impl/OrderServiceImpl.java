package com.mymicroservice.order.service.impl;

import io.micrometer.core.instrument.Metrics;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.CommonAutoConfiguration;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.order.clients.InventoryClient;
import com.mymicroservice.order.clients.InventoryClient.ReserveResult;
import com.mymicroservice.order.clients.ProductClient;
import com.mymicroservice.order.clients.ProductClient.Product;
import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.CreateOrderResult;
import com.mymicroservice.order.dtos.GetOrdersReqDto;
import com.mymicroservice.order.dtos.GetOrdersResDto;
import com.mymicroservice.order.dtos.InternalOrderResDto;
import com.mymicroservice.order.dtos.OrderResDto;
import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.entities.OrderItem;
import com.mymicroservice.order.enums.OrderStatus;
import com.mymicroservice.order.enums.ReservationStatus;
import com.mymicroservice.order.exception.OrderErrorCode;
import com.mymicroservice.order.repositories.OrderRepository;
import com.mymicroservice.order.service.OrderService;
import com.mymicroservice.order.support.UuidV7;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final AuditorAware<UUID> auditorAware;

    public OrderServiceImpl(OrderRepository orderRepository, ProductClient productClient,
            InventoryClient inventoryClient,
            @Qualifier(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME) AuditorAware<UUID> auditorAware) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.auditorAware = auditorAware;
    }

    // 不加 @Transactional：呼叫 product、inventory 時不能佔著 DB 連線；只有最後存檔在交易內（repository 自己的交易）
    // 0. 有 Idempotency-Key 而且之前建過：直接回那一張（不查商品、不預扣，商品之後被下架也一樣回原本的訂單）
    // 1. 查商品  2. 預扣庫存  3. 存訂單；預扣之後的步驟失敗都要釋放，不能讓庫存被扣住
    @Override
    public CreateOrderResult createOrder(UUID userId, CreateOrderReqDto createOrderReqDto, String idempotencyKey) {
        Map<UUID, Integer> quantities = mergeByProduct(createOrderReqDto.items());
        String requestHash = requestHash(quantities);

        if (idempotencyKey != null) {
            Optional<Order> replay = findReplay(userId, idempotencyKey, requestHash);
            if (replay.isPresent()) {
                Metrics.counter("orders.created", "result", "replayed").increment();
                return new CreateOrderResult(OrderResDto.from(replay.get()), false);
            }
        }

        Map<UUID, Product> products = loadPurchasableProducts(quantities);

        UUID orderId = UuidV7.generate();
        Order order = buildOrder(orderId, userId, quantities, products);
        if (idempotencyKey != null) {
            order.setIdempotencyKey(idempotencyKey);
            order.setIdempotencyHash(requestHash);
        }
        reserveStock(order);

        try {
            orderRepository.saveAndFlush(order);
        } catch (RuntimeException exception) {
            // 預扣成功但訂單沒存起來：釋放失敗只能記 log（訂單不存在，排程也補不到），之後靠 inventory 的逾時釋放
            if (!inventoryClient.release(orderId)) {
                log.error("order {} was not saved and its reservation could not be released", orderId);
            }
            // 同一個 key 的另一個請求搶先存檔（唯一約束擋下這一個）：這個請求預扣的庫存已經釋放，回搶先的那一張
            if (idempotencyKey != null && exception instanceof DataIntegrityViolationException) {
                Optional<Order> winner = findReplay(userId, idempotencyKey, requestHash);
                if (winner.isPresent()) {
                    Metrics.counter("orders.created", "result", "replayed").increment();
                    return new CreateOrderResult(OrderResDto.from(winner.get()), false);
                }
            }
            throw exception;
        }
        Metrics.counter("orders.created", "result", "created").increment();
        return new CreateOrderResult(OrderResDto.from(order), true);
    }

    // 同一個使用者、同一個 key 的既有訂單；內容和這次不一樣就拒絕
    private Optional<Order> findReplay(UUID userId, String idempotencyKey, String requestHash) {
        Optional<Order> existing = orderRepository.findWithItemsByUserIdAndIdempotencyKey(userId, idempotencyKey);
        existing.ifPresent(order -> {
            if (!requestHash.equals(order.getIdempotencyHash())) {
                throw new AppException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
        });
        return existing;
    }

    // 請求內容的指紋：商品與數量（重複的商品已經合併），依商品 id 排序，所以品項順序不同也算同一份內容
    private static String requestHash(Map<UUID, Integer> quantities) {
        String canonical = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(";"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // 只能看自己的；ADMIN 可以看全部
    // 別人的訂單和不存在一樣回 404，不透露這個 id 存在
    @Override
    @Transactional(readOnly = true)
    public OrderResDto getOrder(UUID orderId, UUID userId, boolean admin) {
        return OrderResDto.from(findVisibleOrder(orderId, userId, admin));
    }

    // 一般使用者只列出自己的（忽略 userId 參數）；ADMIN 列出全部，可用 userId 篩選
    @Override
    @Transactional(readOnly = true)
    public GetOrdersResDto getOrders(GetOrdersReqDto getOrdersReqDto, UUID userId, boolean admin) {
        UUID ownerFilter = admin ? getOrdersReqDto.userId() : userId;
        Specification<Order> specification = Specification.unrestricted();
        if (ownerFilter != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("userId"), ownerFilter));
        }
        if (getOrdersReqDto.status() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"),
                    getOrdersReqDto.status()));
        }
        return GetOrdersResDto.from(orderRepository.findAll(specification, getOrdersReqDto.toPageable()));
    }

    // 內部查詢（payment-service）：不檢查擁有者，不需要明細
    @Override
    @Transactional(readOnly = true)
    public InternalOrderResDto getInternalOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(InternalOrderResDto::from)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
    }

    // 付款成功（payment-service 呼叫）：PENDING_PAYMENT → PAID，commit 後呼叫 inventory 確認扣除
    // 已經付款的回 200（冪等），還沒同步的會重試確認；已取消的回 409
    // 確認失敗不影響結果：reservationSynced 維持 false，由排程重試
    @Override
    public InternalOrderResDto markPaid(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND, orderId));

        boolean paid = orderRepository.markPaidIfPending(orderId, Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        if (paid) {
            Metrics.counter("orders.paid").increment();
        } else {
            order = orderRepository.findById(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.CANCELLED) {
                throw new AppException(OrderErrorCode.ORDER_ALREADY_CANCELLED, orderId);
            }
            // PAID 之後的狀態（SHIPPED、COMPLETED）也算已付款
            if (order.isReservationSynced() || order.getReservationStatus() != ReservationStatus.CONFIRMED) {
                return InternalOrderResDto.from(order);
            }
        }

        syncReservation(orderId, ReservationStatus.CONFIRMED);
        return InternalOrderResDto.from(orderRepository.findById(orderId).orElseThrow());
    }

    // 只有 PENDING_PAYMENT 可以取消；已經取消的回 200（冪等）；別人的訂單回 404
    // 不加 @Transactional：狀態改完並 commit 後才呼叫 inventory，呼叫期間不佔著 DB 連線
    // 釋放失敗不影響取消的結果：reservationSynced 維持 false，由排程重試
    @Override
    public OrderResDto cancelOrder(UUID orderId, UUID userId, boolean admin) {
        findVisibleOrder(orderId, userId, admin);

        boolean cancelled = orderRepository.cancelIfPending(orderId, Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        if (cancelled) {
            Metrics.counter("orders.cancelled", "reason", "user").increment();
        } else {
            // 狀態不對：重新讀取，可能剛好被別的請求取消（冪等），或已經付款
            Order order = findVisibleOrder(orderId, userId, admin);
            if (order.getStatus() != OrderStatus.CANCELLED) {
                throw new AppException(OrderErrorCode.ORDER_NOT_CANCELLABLE, orderId, order.getStatus());
            }
            if (order.isReservationSynced()) {
                return OrderResDto.from(order);
            }
        }

        // 新取消的，或之前取消但釋放還沒成功的：呼叫 inventory 釋放（inventory 那邊是冪等的）
        syncReservation(orderId, ReservationStatus.RELEASED);
        return OrderResDto.from(findVisibleOrder(orderId, userId, admin));
    }

    // 只有 PAID 可以出貨；已經出貨的回 200（冪等，重複按出貨不會出錯）；其他狀態回 409
    // 只呼叫 ADMIN，所以不檢查擁有者；不需要呼叫其他服務
    @Override
    public OrderResDto shipOrder(UUID orderId) {
        findOrderWithItems(orderId);

        boolean shipped = orderRepository.markShippedIfPaid(orderId, Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        Order order = findOrderWithItems(orderId);
        if (!shipped && order.getStatus() != OrderStatus.SHIPPED) {
            throw new AppException(OrderErrorCode.ORDER_NOT_SHIPPABLE, orderId, order.getStatus());
        }
        return OrderResDto.from(order);
    }

    // 只有 SHIPPED 可以完成；已經完成的回 200（冪等）；其他狀態回 409
    @Override
    public OrderResDto completeOrder(UUID orderId) {
        findOrderWithItems(orderId);

        boolean completed = orderRepository.markCompletedIfShipped(orderId, Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        Order order = findOrderWithItems(orderId);
        if (!completed && order.getStatus() != OrderStatus.COMPLETED) {
            throw new AppException(OrderErrorCode.ORDER_NOT_COMPLETABLE, orderId, order.getStatus());
        }
        return OrderResDto.from(order);
    }

    // 和使用者取消一樣：條件式 UPDATE 保證和付款通知同時發生時只有一個成功
    @Override
    public boolean expireUnpaidOrder(UUID orderId) {
        boolean cancelled = orderRepository.cancelIfPending(orderId, Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        if (cancelled) {
            Metrics.counter("orders.cancelled", "reason", "timeout").increment();
            syncReservation(orderId, ReservationStatus.RELEASED);
        }
        return cancelled;
    }

    @Override
    public boolean retryReservationSync(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.isReservationSynced()
                || order.getReservationStatus() == ReservationStatus.RESERVED) {
            return order != null;
        }
        return syncReservation(orderId, order.getReservationStatus());
    }

    // 呼叫 inventory 讓預扣變成 target 狀態；成功才標成已同步，失敗留給排程重試
    private boolean syncReservation(UUID orderId, ReservationStatus target) {
        boolean success = switch (target) {
            case RELEASED -> inventoryClient.release(orderId);
            case CONFIRMED -> inventoryClient.confirm(orderId);
            case RESERVED -> throw new IllegalArgumentException("cannot sync to RESERVED");
        };
        if (success) {
            orderRepository.markReservationSynced(orderId, target);
        }
        return success;
    }

    private Order findVisibleOrder(UUID orderId, UUID userId, boolean admin) {
        return orderRepository.findWithItemsById(orderId)
                .filter(order -> admin || order.getUserId().equals(userId))
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
    }

    private Order findOrderWithItems(UUID orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
    }

    // 不存在的商品 → 404；沒有上架的 → 409；兩種都列出全部有問題的
    private Map<UUID, Product> loadPurchasableProducts(Map<UUID, Integer> quantities) {
        Map<UUID, Product> products = productClient.findByIds(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::id, Function.identity(), (first, second) -> first));

        List<String> missing = quantities.keySet().stream()
                .filter(productId -> !products.containsKey(productId))
                .map(UUID::toString)
                .toList();
        if (!missing.isEmpty()) {
            throw new AppException(OrderErrorCode.PRODUCT_NOT_FOUND, String.join(", ", missing));
        }
        List<String> unavailable = quantities.keySet().stream()
                .map(products::get)
                .filter(product -> !product.isActive())
                .map(product -> product.id().toString())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new AppException(OrderErrorCode.PRODUCT_NOT_AVAILABLE, String.join(", ", unavailable));
        }
        return products;
    }

    private Order buildOrder(UUID orderId, UUID userId, Map<UUID, Integer> quantities, Map<UUID, Product> products) {
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setReservationStatus(ReservationStatus.RESERVED);
        order.setReservationSynced(true);

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            Product product = products.get(entry.getKey());
            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setSku(product.sku());
            item.setProductName(product.name());
            item.setUnitPrice(product.price());
            item.setQty(entry.getValue());
            item.setSubtotal(product.price().multiply(BigDecimal.valueOf(entry.getValue())));
            order.addItem(item);
            total = total.add(item.getSubtotal());
        }
        order.setTotalAmount(total);
        // 回應的明細依 SKU 排序，和從 DB 讀出（@OrderBy("sku")）一致
        order.getItems().sort((a, b) -> a.getSku().compareTo(b.getSku()));
        return order;
    }

    private void reserveStock(Order order) {
        List<InventoryClient.Item> items = order.getItems().stream()
                .map(item -> new InventoryClient.Item(item.getSku(), item.getQty()))
                .toList();
        ReserveResult result = inventoryClient.reserve(order.getId(), items);
        switch (result.outcome()) {
            case RESERVED -> {
            }
            case OUT_OF_STOCK -> throw new AppException(OrderErrorCode.OUT_OF_STOCK, extractSkus(result.detail()));
            // 熔斷打開，請求沒有送出：確定沒有預扣，不必釋放
            case UNAVAILABLE -> throw new AppException(OrderErrorCode.INVENTORY_SERVICE_UNAVAILABLE);
            case UNKNOWN -> {
                // 不知道有沒有扣成功：釋放（inventory 會留下紀錄，延遲送達的預扣也會被擋下）
                if (!inventoryClient.release(order.getId())) {
                    log.error("reservation of order {} is unknown and could not be released", order.getId());
                }
                throw new AppException(OrderErrorCode.INVENTORY_SERVICE_UNAVAILABLE);
            }
        }
    }

    // inventory 的錯誤訊息格式是 "insufficient stock: SKU-A, SKU-B."，取出 SKU 清單；格式不符就整段回傳
    private static String extractSkus(String detail) {
        if (detail == null) {
            return "unknown";
        }
        int colon = detail.indexOf(": ");
        String skus = colon < 0 ? detail : detail.substring(colon + 2);
        return skus.endsWith(".") ? skus.substring(0, skus.length() - 1) : skus;
    }

    // 合併重複的商品，保留第一次出現的順序
    private static Map<UUID, Integer> mergeByProduct(List<CreateOrderReqDto.Item> items) {
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        items.forEach(item -> merged.merge(item.productId(), item.qty(), Integer::sum));
        return merged;
    }
}
