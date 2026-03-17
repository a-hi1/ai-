$ErrorActionPreference = 'Stop'
$user = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/users/login -ContentType 'application/json' -Body '{"email":"demo@aishop.local","password":"123456"}'
Invoke-RestMethod -Method Delete -Uri ("http://127.0.0.1:8080/api/cart/user/" + $user.id) | Out-Null
$products = Invoke-RestMethod -Method Get -Uri http://127.0.0.1:8080/api/products
$first = $products[0]
$second = $products[1]
$r1 = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/cart/items -ContentType 'application/json' -Body (@{ userId = $user.id; productId = $first.id; quantity = 1 } | ConvertTo-Json)
$r2 = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/cart/items -ContentType 'application/json' -Body (@{ userId = $user.id; productId = $second.id; quantity = 2 } | ConvertTo-Json)
Invoke-RestMethod -Method Put -Uri ("http://127.0.0.1:8080/api/cart/items/" + $r1.cartItemId) -ContentType 'application/json' -Body (@{ quantity = 3 } | ConvertTo-Json) | Out-Null
$cart = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8080/api/cart/" + $user.id)
$order = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/orders -ContentType 'application/json' -Body (@{ userId = $user.id; totalAmount = 0 } | ConvertTo-Json)
$payment = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/payments/alipay/create -ContentType 'application/json' -Body (@{ orderId = $order.id } | ConvertTo-Json)
$notify = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/payments/alipay/notify -ContentType 'application/json' -Body (@{ orderId = $order.id; status = 'PAID'; gatewayTradeNo = $payment.gatewayTradeNo } | ConvertTo-Json)
$orders = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8080/api/orders/user/" + $user.id)
$orderDetail = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8080/api/orders/" + $order.id)
$chatReply = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/chat/send -ContentType 'application/json' -Body (@{ userId = $user.id; message = 'Need commuting noise cancelling headphones' } | ConvertTo-Json)
$history = Invoke-RestMethod -Method Get -Uri ("http://127.0.0.1:8080/api/chat/history/" + $user.id)
$content = 'USER=' + ($user | ConvertTo-Json -Compress) + [Environment]::NewLine +
  'CART=' + ($cart | ConvertTo-Json -Depth 5 -Compress) + [Environment]::NewLine +
  'ORDER=' + ($order | ConvertTo-Json -Depth 5 -Compress) + [Environment]::NewLine +
  'PAYMENT=' + ($payment | ConvertTo-Json -Compress) + [Environment]::NewLine +
  'NOTIFY=' + $notify + [Environment]::NewLine +
  'ORDERS=' + ($orders | ConvertTo-Json -Depth 5 -Compress) + [Environment]::NewLine +
  'ORDER_DETAIL=' + ($orderDetail | ConvertTo-Json -Depth 6 -Compress) + [Environment]::NewLine +
  'CHAT_REPLY=' + ($chatReply | ConvertTo-Json -Compress) + [Environment]::NewLine +
  'HISTORY_COUNT=' + @($history).Count
[System.IO.File]::WriteAllText('d:\zhuomian\workspace\e2e-flow.log', $content)
