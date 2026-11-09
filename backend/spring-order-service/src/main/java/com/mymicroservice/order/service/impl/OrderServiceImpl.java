package com.mymicroservice.order.service.impl;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.order.clients.InventoryClient;
import com.mymicroservice.order.clients.InventoryClient.ReserveResult;
import com.mymicroservice.order.clients.ProductClient;
import com.mymicroservice.order.clients.ProductClient.Product;
import com.mymicroservice.order.dtos.CreateOrderReqDto;
import com.mymicroservice.order.dtos.GetOrdersReqDto;
import com.mymicroservice.order.dtos.GetOrdersResDto;
import com.mymicroservice.order.dtos.OrderResDto;
import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.entities.OrderItem;
import com.mymicroservice.order.enums.OrderStatus;
import com.mymicroservice.order.enums.ReservationStatus;
import com.mymicroservice.order.exception.OrderErrorCode;
import com.mymicroservice.order.repositories.OrderRepository;
import com.mymicroservice.order.service.OrderService;
import com.mymicroservice.order.support.UuidV7;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;

    // 不加 @Transactional：呼叫 product、inventory 時不能佔著 DB 連線；只有最後存檔在交易內（repository 自己的交易）
    // 1. 查商品  2. 預扣庫存  3. 存訂單；預扣之後的步驟失敗都要釋放，不能讓庫存被扣住
    @Override
    public OrderResDto createOrder(UUID userId, CreateOrderReqDto createOrderReqDto) {
        Map<UUID, Integer> quantities = mergeByProduct(createOrderReqDto.items());
        Map<UUID, Product> products = loadPurchasableProducts(quantities);

        UUID orderId = UuidV7.generate();
        Order order = buildOrder(orderId, userId, quantities, products);
        reserveStock(order);

        try {
            orderRepository.saveAndFlush(order);
        } catch (RuntimeException exception) {
            // 預扣成功但訂單沒存起來：釋放失敗只能記 log（訂單不存在，排程也補不到），之後靠 inventory 的逾時釋放
            if (!inventoryClient.release(orderId)) {
                log.error("order {} was not saved and its reservation could not be released", orderId);
            }
            throw exception;
        }
        return OrderResDto.from(order);
    }

    // 只能看自己的；ADMIN 可以看全部
    // 別人的訂單和不存在一樣回 404，不透露這個 id 存在
    @Override
    @Transactional(readOnly = true)
    public OrderResDto getOrder(UUID orderId, UUID userId, boolean admin) {
        return orderRepository.findWithItemsById(orderId)
                .filter(order -> admin || order.getUserId().equals(userId))
                .map(OrderResDto::from)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
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
