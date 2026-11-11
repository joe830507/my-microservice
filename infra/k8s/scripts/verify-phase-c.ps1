param([string]$Context = 'kind-my-microservice')
$ErrorActionPreference = 'Stop'
$ns = 'my-microservice'
function Forward($service, $mapping) {
    $process = Start-Process kubectl -ArgumentList @('--context',$Context,'-n',$ns,'port-forward',"svc/$service",$mapping) -WindowStyle Hidden -PassThru -RedirectStandardOutput "backend/phase-c-forward-$service.log" -RedirectStandardError "backend/phase-c-forward-$service.err"
    Start-Sleep -Seconds 2
    return $process
}
$orderPort = Get-Random -Minimum 20000 -Maximum 30000
$orderForward = Forward order-service "${orderPort}:8082"
function Kube { & kubectl --context $Context -n $ns @args; if ($LASTEXITCODE -ne 0) { throw "kubectl failed: $args" } }
function Sql($service, $query) {
    $query | & kubectl --context $Context -n $ns exec -i "$service-db-0" -- sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $service" }
}
function Api($port, $path, $body, $method = 'POST') {
    Invoke-RestMethod "http://127.0.0.1:$port$path" -Method $method -Headers $headers -ContentType application/json -Body ($body | ConvertTo-Json -Depth 8)
}
function AwaitSql($service, $query, $expected) {
    for ($i = 0; $i -lt 45; $i++) {
        $actual = Sql $service $query
        if ($actual -eq $expected) { return }
        Start-Sleep -Seconds 2
    }
    throw "Expected $expected, got $actual"
}
$user = [guid]::NewGuid().ToString()
$product = [guid]::NewGuid().ToString()
$sku = 'phase-c-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$headers = @{ 'X-User-Id' = $user; 'X-User-Roles' = 'ADMIN'; traceparent = '00-' + [guid]::NewGuid().ToString('N') + '-0123456789abcdef-01' }
Sql product "INSERT INTO product_service.product(id,sku,name,price,status,created_by,updated_by) VALUES ('$product','$sku','Phase C acceptance fixture',10,'ACTIVE','$user','$user');"
Sql inventory "INSERT INTO inventory_service.stock(sku,available,reserved,created_by,updated_by) VALUES ('$sku',20,0,'$user','$user');"
$orders = @()
for ($i = 0; $i -lt 3; $i++) {
    $orders += Api $orderPort '/api/v1/orders' @{items = @(@{productId=$product;qty=1})}
}
$payments = @()
foreach ($order in $orders[0..1]) { $payments += Api 18085 '/api/v1/payments' @{orderId=$order.id} }
try {
    Kube scale deployment/order-service --replicas=0
    Kube wait --for=delete pod -l app=order-service --timeout=90s
    $paid = Api 18085 "/api/v1/payments/$($payments[0].id)/mock-callback" @{result='SUCCESS'}
    if ($paid.status -ne 'SUCCEEDED') { throw 'Payment did not succeed during order outage' }
    AwaitSql order "SELECT status FROM order_service.orders WHERE id='$($orders[0].id)'" 'PENDING_PAYMENT'
    Write-Output "C4 payment succeeded while order stopped: $($orders[0].id)"
} finally { Kube scale deployment/order-service --replicas=2 }
AwaitSql order "SELECT status FROM order_service.orders WHERE id='$($orders[0].id)'" 'PAID'
AwaitSql inventory "SELECT status FROM inventory_service.reservation WHERE order_id='$($orders[0].id)'" 'CONFIRMED'
Write-Output 'C4 recovered: PAID; reservation CONFIRMED'
$orderPort = Get-Random -Minimum 20000 -Maximum 30000
$orderForward = Forward order-service "${orderPort}:8082"
try {
    Kube scale deployment/inventory-service --replicas=0
    Kube wait --for=delete pod -l app=inventory-service --timeout=90s
    $paid = Api 18085 "/api/v1/payments/$($payments[1].id)/mock-callback" @{result='SUCCESS'}
    $cancelled = Api $orderPort "/api/v1/orders/$($orders[2].id)/cancel" @{}
    if ($paid.status -ne 'SUCCEEDED' -or $cancelled.status -ne 'CANCELLED') { throw 'C5 API results differ' }
    AwaitSql order "SELECT status FROM order_service.orders WHERE id='$($orders[1].id)'" 'PAID'
    AwaitSql inventory "SELECT status FROM inventory_service.reservation WHERE order_id='$($orders[1].id)'" 'RESERVED'
    Write-Output 'C5 payment and cancellation succeeded while inventory stopped'
} finally { Kube scale deployment/inventory-service --replicas=2 }
AwaitSql inventory "SELECT status FROM inventory_service.reservation WHERE order_id='$($orders[1].id)'" 'CONFIRMED'
AwaitSql inventory "SELECT status FROM inventory_service.reservation WHERE order_id='$($orders[2].id)'" 'RELEASED'
AwaitSql inventory "SELECT available || ':' || reserved FROM inventory_service.stock WHERE sku='$sku'" '18:0'
Write-Output 'C5 recovered: CONFIRMED, RELEASED; available=18 reserved=0'
Write-Output "C5_READY user=$user paidOrder=$($orders[1].id) cancelOrder=$($orders[2].id) payment=$($payments[1].id) sku=$sku traceparent=$($headers.traceparent)"
$state = @{user=$user; paidOrder=$orders[1].id; cancelOrder=$orders[2].id; payment=$payments[1].id; sku=$sku; traceparent=$headers.traceparent}
$state | ConvertTo-Json | Set-Content -Encoding utf8 backend/phase-c-acceptance-state.json
