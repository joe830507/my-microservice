param([string]$Context = 'kind-my-microservice')
$ErrorActionPreference = 'Stop'
$namespace = 'my-microservice'
$evidence = Join-Path (Get-Location) 'docs/implementation-plans/middleware/evidence/phase-d'
[IO.Directory]::CreateDirectory($evidence) | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Kube {
    $result = & kubectl --context $Context -n $namespace @args
    if ($LASTEXITCODE -ne 0) { throw "kubectl failed: $args" }
    return $result
}
$pod = 'phase-d-denied-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$forward = $null
$forwardOut = Join-Path (Get-Location) 'phase-d-forward.tmp.log'
$forwardErr = Join-Path (Get-Location) 'phase-d-forward.tmp.err'
try {
    Kube rollout status statefulset/elasticsearch --timeout=180s
    # 用實際商品 Pod 作對照，不建立會被 Service selector 選到的假商品 Pod。
    Kube @('exec', 'deployment/product-service', '--', 'nc', '-z', '-w', '3', 'elasticsearch', '9200')
    Kube @('run', $pod, '--image=busybox:1.37', '--labels=app=phase-d-denied', '--restart=Never', '--command', '--', 'sh', '-c', 'if nc -z -w 3 elasticsearch 9200; then echo unexpected-access; exit 1; else echo denied; fi')
    Kube wait "pod/$pod" '--for=jsonpath={.status.phase}=Succeeded' --timeout=90s
    $denied = (Kube logs $pod) -join "`n"
    if ($denied.Trim() -ne 'denied') { throw "Unexpected network result: $denied" }
    [IO.File]::WriteAllText((Join-Path $evidence 'd1-network.txt'), "context=$Context`nproduct-service -> elasticsearch:9200 = allowed`nunauthorized Pod -> elasticsearch:9200 = denied`n", $utf8)
    $port = Get-Random -Minimum 40000 -Maximum 50000
    $forward = Start-Process kubectl -ArgumentList @('--context', $Context, '-n', $namespace, 'port-forward', 'svc/elasticsearch', "${port}:9200") -WindowStyle Hidden -PassThru -RedirectStandardOutput $forwardOut -RedirectStandardError $forwardErr
    # Python 的健康輪詢會等 port-forward 與 ES 就緒，不依賴固定啟動時間。
    & python infra/elasticsearch/verify-analysis.py --url "http://127.0.0.1:$port" --output (Join-Path $evidence 'd1-kind-analysis.json')
    if ($LASTEXITCODE -ne 0) { throw 'kind analyzer verification failed' }
    Write-Output 'D1 kind network isolation and analyzer verification passed'
} finally {
    if ($null -ne $forward) { Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue }
    & kubectl --context $Context -n $namespace delete pod $pod --ignore-not-found=true | Out-Null
    Remove-Item -LiteralPath $forwardOut, $forwardErr -ErrorAction SilentlyContinue
}
