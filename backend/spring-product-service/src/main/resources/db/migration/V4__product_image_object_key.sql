-- 圖片改存物件儲存的 object key（products/{productId}/{uuid}.{ext}），對外網址在回應時組
-- 換 CDN 網域或換成 AWS S3 時不用改資料
--
-- 原本的 url 都是手動填的外部網址，物件儲存裡沒有對應的檔案，無法轉換，直接刪除（只有開發資料）
-- 上架中的商品必須有主圖：刪掉圖片後改成 INACTIVE，重新上傳主圖後再上架
UPDATE product_service.product SET status = 'INACTIVE'
WHERE status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM product_service.product_image i WHERE i.product_id = product.id);

DELETE FROM product_service.product_image;

ALTER TABLE product_service.product_image DROP COLUMN url;
ALTER TABLE product_service.product_image ADD COLUMN object_key VARCHAR(500) NOT NULL;

-- 同一個檔案不能登記兩次
ALTER TABLE product_service.product_image ADD CONSTRAINT uq_product_image_object_key UNIQUE (object_key);
