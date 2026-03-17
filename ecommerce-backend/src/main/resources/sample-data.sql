INSERT INTO users (id, email, password_hash, role, display_name, phone, city, bio, created_at)
VALUES (
  '11111111-1111-1111-1111-111111111111',
  'demo@aishop.local',
  'HASHED:123456',
  'USER',
  'AI 导购体验账号',
  '13800138000',
  'Shanghai',
  '偏好数码、效率工具、通勤装备和运动穿戴商品',
  TIMESTAMP '2026-03-01 09:00:00'
)
ON CONFLICT (id) DO UPDATE SET
  email = EXCLUDED.email,
  password_hash = EXCLUDED.password_hash,
  role = EXCLUDED.role,
  display_name = EXCLUDED.display_name,
  phone = EXCLUDED.phone,
  city = EXCLUDED.city,
  bio = EXCLUDED.bio,
  created_at = EXCLUDED.created_at;

INSERT INTO products (id, name, description, price, image_url, tags, data_source, created_at)
VALUES
  ('20000000-0000-0000-0000-000000000001', '联想小新 Pro 14', '14 英寸轻薄办公笔记本，32GB 内存，1TB 固态，适合通勤与高效办公', 6299.00, 'https://picsum.photos/seed/laptop/480/320', '笔记本,laptop,办公,office,轻薄,通勤', 'SAMPLE', TIMESTAMP '2026-03-01 09:10:00'),
  ('20000000-0000-0000-0000-000000000002', '华硕灵耀 14 Air', '高分辨率 OLED 轻薄本，适合移动办公、内容创作和差旅携带', 6999.00, 'https://picsum.photos/seed/zenbook/480/320', '笔记本,laptop,轻薄,oled,办公,创作', 'SAMPLE', TIMESTAMP '2026-03-01 09:11:00'),
  ('20000000-0000-0000-0000-000000000003', '索尼 WH-1000XM5 降噪耳机', '头戴式无线降噪耳机，适合通勤、地铁和长时间音乐聆听', 2299.00, 'https://picsum.photos/seed/headphone/480/320', '耳机,headphone,音频,audio,降噪,通勤', 'SAMPLE', TIMESTAMP '2026-03-01 09:12:00'),
  ('20000000-0000-0000-0000-000000000004', '华为 FreeBuds Pro 4', '入耳式主动降噪耳机，适合电话会议、地铁通勤和长续航需求', 1499.00, 'https://picsum.photos/seed/freebuds/480/320', '耳机,headphone,降噪,通勤,会议,蓝牙', 'SAMPLE', TIMESTAMP '2026-03-01 09:13:00'),
  ('20000000-0000-0000-0000-000000000005', 'Apple Watch Series 9 智能手表', '支持心率、运动和健康监测的智能手表，适合健身与日常提醒', 2999.00, 'https://picsum.photos/seed/watch/480/320', '手表,watch,wearable,健康,运动', 'SAMPLE', TIMESTAMP '2026-03-01 09:14:00'),
  ('20000000-0000-0000-0000-000000000006', 'Garmin Forerunner 265', '偏运动训练的智能手表，适合跑步、心率监测和高频训练计划', 2680.00, 'https://picsum.photos/seed/garmin/480/320', '手表,watch,运动,训练,跑步,健康', 'SAMPLE', TIMESTAMP '2026-03-01 09:15:00'),
  ('20000000-0000-0000-0000-000000000007', '任天堂 Switch OLED 游戏机', '7 英寸 OLED 屏的便携游戏主机，适合家庭娱乐和掌机体验', 2399.00, 'https://picsum.photos/seed/switch/480/320', '游戏机,console,game,娱乐,掌机', 'SAMPLE', TIMESTAMP '2026-03-01 09:16:00'),
  ('20000000-0000-0000-0000-000000000008', '小米智能手环 9', '价格友好的运动手环，续航长，适合日常健康和睡眠监测', 299.00, 'https://picsum.photos/seed/band/480/320', '手环,smart band,wearable,fitness,运动,健康', 'SAMPLE', TIMESTAMP '2026-03-01 09:17:00'),
  ('20000000-0000-0000-0000-000000000009', '华为手环 9 NFC 版', '支持睡眠、心率和刷卡能力的轻量运动手环，适合日常佩戴', 379.00, 'https://picsum.photos/seed/hband/480/320', '手环,band,wearable,健康,运动,NFC', 'SAMPLE', TIMESTAMP '2026-03-01 09:18:00'),
  ('20000000-0000-0000-0000-000000000010', 'K87 机械键盘', '紧凑布局机械键盘，支持热插拔，适合办公与游戏', 499.00, 'https://picsum.photos/seed/keyboard/480/320', '键盘,keyboard,机械键盘,office,gaming,办公', 'SAMPLE', TIMESTAMP '2026-03-01 09:19:00'),
  ('20000000-0000-0000-0000-000000000011', 'Keychron K8 Pro', '支持蓝牙与有线双模的机械键盘，适合 Mac 和 Windows 混合办公', 699.00, 'https://picsum.photos/seed/keychron/480/320', '键盘,keyboard,机械键盘,蓝牙,办公,效率', 'SAMPLE', TIMESTAMP '2026-03-01 09:20:00'),
  ('20000000-0000-0000-0000-000000000012', '罗技 MX Keys S', '静音输入体验更好的高效办公键盘，适合长时间文档和表格处理', 799.00, 'https://picsum.photos/seed/mxkeys/480/320', '键盘,keyboard,办公,静音,效率,蓝牙', 'SAMPLE', TIMESTAMP '2026-03-01 09:21:00'),
  ('20000000-0000-0000-0000-000000000013', '戴尔 UltraSharp 27 4K 显示器', '27 英寸 4K 办公显示器，适合文档、多窗口和轻度设计工作', 3299.00, 'https://picsum.photos/seed/monitor27/480/320', '显示器,monitor,4k,办公,设计,桌面', 'SAMPLE', TIMESTAMP '2026-03-01 09:22:00'),
  ('20000000-0000-0000-0000-000000000014', '小米平板 6S Pro', '适合移动办公、轻娱乐和视频会议的大屏平板设备', 3299.00, 'https://picsum.photos/seed/tablet/480/320', '平板,tablet,办公,会议,娱乐,移动', 'SAMPLE', TIMESTAMP '2026-03-01 09:23:00'),
  ('20000000-0000-0000-0000-000000000015', 'Anker 737 氮化镓充电器', '多口快充，适合笔记本、手机和耳机一起补电', 399.00, 'https://picsum.photos/seed/charger/480/320', '充电器,charger,快充,差旅,办公,通勤', 'SAMPLE', TIMESTAMP '2026-03-01 09:24:00'),
  ('20000000-0000-0000-0000-000000000016', '罗技 MX Master 3S 鼠标', '高精度静音办公鼠标，适合多设备切换和长时间操作', 699.00, 'https://picsum.photos/seed/mouse/480/320', '鼠标,mouse,办公,静音,效率,蓝牙', 'SAMPLE', TIMESTAMP '2026-03-01 09:25:00'),
  ('20000000-0000-0000-0000-000000000017', 'GoPro HERO12 Black', '适合骑行、旅行和户外记录的运动相机', 2799.00, 'https://picsum.photos/seed/gopro/480/320', '相机,camera,运动,旅行,户外,拍摄', 'SAMPLE', TIMESTAMP '2026-03-01 09:26:00'),
  ('20000000-0000-0000-0000-000000000018', '极米 Halo+ 便携投影仪', '适合宿舍、家庭影音和小型会议的便携投影设备', 4299.00, 'https://picsum.photos/seed/projector/480/320', '投影仪,projector,影音,会议,家庭,娱乐', 'SAMPLE', TIMESTAMP '2026-03-01 09:27:00'),
  ('20000000-0000-0000-0000-000000000019', '苏泊尔轻量不粘炒锅', '适合租房和家庭快手菜的轻量炒锅，日常清洁更省力', 229.00, 'https://picsum.photos/seed/wok/480/320', '厨房,锅具,炒锅,家居,做饭,厨具', 'SAMPLE', TIMESTAMP '2026-03-01 09:28:00'),
  ('20000000-0000-0000-0000-000000000020', '碧然德即热净饮机', '桌面即热净饮设备，适合家庭和办公室快速取热水', 1699.00, 'https://picsum.photos/seed/water/480/320', '厨房,净饮,饮水机,家居,办公,小家电', 'SAMPLE', TIMESTAMP '2026-03-01 09:29:00'),
  ('20000000-0000-0000-0000-000000000021', '维达云柔抽纸 24 包', '家用和办公室常备抽纸，整箱补货更省心', 79.00, 'https://picsum.photos/seed/tissue/480/320', '家居,纸品,清洁,抽纸,日用,补货', 'SAMPLE', TIMESTAMP '2026-03-01 09:30:00'),
  ('20000000-0000-0000-0000-000000000022', '汰渍洗衣凝珠 52 颗', '适合家庭囤货的洗衣凝珠，省时省量，留香持久', 69.00, 'https://picsum.photos/seed/laundry/480/320', '家居,清洁,洗衣,凝珠,日化,补货', 'SAMPLE', TIMESTAMP '2026-03-01 09:31:00'),
  ('20000000-0000-0000-0000-000000000023', 'CeraVe 适乐肤修护润肤乳', '适合干皮和换季场景的保湿修护乳，面部和身体都可使用', 128.00, 'https://picsum.photos/seed/skincare/480/320', '个护,护肤,保湿,修护,乳液,洗护', 'SAMPLE', TIMESTAMP '2026-03-01 09:32:00'),
  ('20000000-0000-0000-0000-000000000024', '欧莱雅男士氨基酸洁面', '适合早晚清洁和出差便携的男士洁面产品', 69.00, 'https://picsum.photos/seed/cleanser/480/320', '个护,洗护,洁面,男士,清洁,护理', 'SAMPLE', TIMESTAMP '2026-03-01 09:33:00'),
  ('20000000-0000-0000-0000-000000000025', 'Babycare 婴儿手口湿巾 80 抽 6 包', '适合外出和居家常备的婴儿湿巾，温和清洁手口', 59.00, 'https://picsum.photos/seed/wipes/480/320', '母婴,婴儿,湿巾,宝宝,清洁,家庭', 'SAMPLE', TIMESTAMP '2026-03-01 09:34:00'),
  ('20000000-0000-0000-0000-000000000026', '好奇铂金装纸尿裤 M 码', '适合宝宝日常使用的轻薄纸尿裤，透气干爽', 169.00, 'https://picsum.photos/seed/diaper/480/320', '母婴,纸尿裤,宝宝,婴儿,家庭,护理', 'SAMPLE', TIMESTAMP '2026-03-01 09:35:00'),
  ('20000000-0000-0000-0000-000000000027', '网易严选冻干双拼猫粮', '高蛋白冻干双拼猫粮，适合成猫日常喂养和适口性提升', 139.00, 'https://picsum.photos/seed/catfood/480/320', '宠物,猫粮,猫咪,家庭,宠粮,日用', 'SAMPLE', TIMESTAMP '2026-03-01 09:36:00'),
  ('20000000-0000-0000-0000-000000000028', '霍尼韦尔宠物外出背包', '适合宠物短途出行和看诊使用的透气外出包', 259.00, 'https://picsum.photos/seed/petbag/480/320', '宠物,宠物包,外出,家庭,猫咪,狗狗', 'SAMPLE', TIMESTAMP '2026-03-01 09:37:00'),
  ('20000000-0000-0000-0000-000000000029', '探路者三合一冲锋衣', '适合徒步、旅行和多天气通勤的防风防泼水外套', 699.00, 'https://picsum.photos/seed/jacket/480/320', '户外,冲锋衣,旅行,通勤,运动,服饰', 'SAMPLE', TIMESTAMP '2026-03-01 09:38:00'),
  ('20000000-0000-0000-0000-000000000030', '迪卡侬防滑瑜伽垫', '适合居家拉伸、瑜伽和轻训练的高回弹瑜伽垫', 119.00, 'https://picsum.photos/seed/yoga/480/320', '运动,瑜伽,训练,居家,健康,户外', 'SAMPLE', TIMESTAMP '2026-03-01 09:39:00'),
  ('20000000-0000-0000-0000-000000000031', '星巴克家享哥伦比亚咖啡豆', '适合手冲和全自动咖啡机的中度烘焙咖啡豆', 98.00, 'https://picsum.photos/seed/coffee/480/320', '食品,咖啡,饮品,提神,生活,早餐', 'SAMPLE', TIMESTAMP '2026-03-01 09:40:00'),
  ('20000000-0000-0000-0000-000000000032', '三只松鼠每日坚果礼盒', '适合办公抽屉、家庭分享和节日送礼的坚果组合', 89.00, 'https://picsum.photos/seed/nuts/480/320', '食品,零食,坚果,礼盒,办公室,生活', 'SAMPLE', TIMESTAMP '2026-03-01 09:41:00'),
  ('20000000-0000-0000-0000-000000000033', '乐高经典创意积木箱', '适合亲子互动和创意启蒙的经典颗粒积木套装', 329.00, 'https://picsum.photos/seed/lego/480/320', '生活,积木,玩具,亲子,创意,家庭', 'SAMPLE', TIMESTAMP '2026-03-01 09:42:00'),
  ('20000000-0000-0000-0000-000000000034', '晨光速干中性笔 12 支装', '适合学生和办公室常备的速干书写套装', 29.00, 'https://picsum.photos/seed/pen/480/320', '文具,书写,办公,学习,生活,补货', 'SAMPLE', TIMESTAMP '2026-03-01 09:43:00'),
  ('20000000-0000-0000-0000-000000000035', '南极人全棉四件套', '适合出租屋和卧室焕新的全棉床品四件套', 259.00, 'https://picsum.photos/seed/bedding/480/320', '家居,家纺,床品,四件套,卧室,生活', 'SAMPLE', TIMESTAMP '2026-03-01 09:44:00'),
  ('20000000-0000-0000-0000-000000000036', '富安娜慢回弹记忆枕', '适合久坐办公和注重睡眠支撑的人群使用的记忆枕', 199.00, 'https://picsum.photos/seed/pillow/480/320', '家居,枕头,睡眠,卧室,家纺,健康', 'SAMPLE', TIMESTAMP '2026-03-01 09:45:00')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  price = EXCLUDED.price,
  image_url = EXCLUDED.image_url,
  tags = EXCLUDED.tags,
  data_source = EXCLUDED.data_source,
  created_at = EXCLUDED.created_at;

INSERT INTO cart_items (id, user_id, product_id, quantity, created_at)
SELECT '30000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000003', 1, TIMESTAMP '2026-03-06 18:00:00'
WHERE NOT EXISTS (SELECT 1 FROM cart_items WHERE id = '30000000-0000-0000-0000-000000000001');

INSERT INTO cart_items (id, user_id, product_id, quantity, created_at)
SELECT '30000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000015', 2, TIMESTAMP '2026-03-06 18:03:00'
WHERE NOT EXISTS (SELECT 1 FROM cart_items WHERE id = '30000000-0000-0000-0000-000000000002');

INSERT INTO orders (id, user_id, status, total_amount, payment_method, gateway_trade_no, paid_at, created_at)
SELECT '40000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'CREATED', 2598.00, 'ALIPAY_SANDBOX', 'SANDBOX-DEMO-2001', NULL, TIMESTAMP '2026-03-08 10:00:00'
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id = '40000000-0000-0000-0000-000000000001');

INSERT INTO orders (id, user_id, status, total_amount, payment_method, gateway_trade_no, paid_at, created_at)
SELECT '40000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'PAID', 8598.00, 'ALIPAY_SANDBOX', 'SANDBOX-DEMO-2002', TIMESTAMP '2026-03-07 15:20:00', TIMESTAMP '2026-03-07 15:00:00'
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id = '40000000-0000-0000-0000-000000000002');

INSERT INTO orders (id, user_id, status, total_amount, payment_method, gateway_trade_no, paid_at, created_at)
SELECT '40000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'CANCELLED', 2698.00, 'ALIPAY_SANDBOX', 'SANDBOX-DEMO-2003', NULL, TIMESTAMP '2026-03-05 09:30:00'
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id = '40000000-0000-0000-0000-000000000003');

INSERT INTO orders (id, user_id, status, total_amount, payment_method, gateway_trade_no, paid_at, created_at)
SELECT '40000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'PAID', 3998.00, 'ALIPAY_SANDBOX', 'SANDBOX-DEMO-2004', TIMESTAMP '2026-03-03 21:05:00', TIMESTAMP '2026-03-03 20:40:00'
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id = '40000000-0000-0000-0000-000000000004');

INSERT INTO order_items (id, order_id, product_id, product_name, product_description, image_url, unit_price, quantity, created_at)
VALUES
  ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', '华为 FreeBuds Pro 4', '入耳式主动降噪耳机，适合电话会议、地铁通勤和长续航需求', 'https://picsum.photos/seed/freebuds/480/320', 1499.00, 1, TIMESTAMP '2026-03-08 10:00:00'),
  ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000011', 'Keychron K8 Pro', '支持蓝牙与有线双模的机械键盘，适合 Mac 和 Windows 混合办公', 'https://picsum.photos/seed/keychron/480/320', 699.00, 1, TIMESTAMP '2026-03-08 10:00:00'),
  ('50000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '联想小新 Pro 14', '14 英寸轻薄办公笔记本，32GB 内存，1TB 固态，适合通勤与高效办公', 'https://picsum.photos/seed/laptop/480/320', 6299.00, 1, TIMESTAMP '2026-03-07 15:00:00'),
  ('50000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000003', '索尼 WH-1000XM5 降噪耳机', '头戴式无线降噪耳机，适合通勤、地铁和长时间音乐聆听', 'https://picsum.photos/seed/headphone/480/320', 2299.00, 1, TIMESTAMP '2026-03-07 15:00:00'),
  ('50000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000007', '任天堂 Switch OLED 游戏机', '7 英寸 OLED 屏的便携游戏主机，适合家庭娱乐和掌机体验', 'https://picsum.photos/seed/switch/480/320', 2399.00, 1, TIMESTAMP '2026-03-05 09:30:00'),
  ('50000000-0000-0000-0000-000000000006', '40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000008', '小米智能手环 9', '价格友好的运动手环，续航长，适合日常健康和睡眠监测', 'https://picsum.photos/seed/band/480/320', 299.00, 1, TIMESTAMP '2026-03-05 09:30:00'),
  ('50000000-0000-0000-0000-000000000007', '40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000013', '戴尔 UltraSharp 27 4K 显示器', '27 英寸 4K 办公显示器，适合文档、多窗口和轻度设计工作', 'https://picsum.photos/seed/monitor27/480/320', 3299.00, 1, TIMESTAMP '2026-03-03 20:40:00'),
  ('50000000-0000-0000-0000-000000000008', '40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000016', '罗技 MX Master 3S 鼠标', '高精度静音办公鼠标，适合多设备切换和长时间操作', 'https://picsum.photos/seed/mouse/480/320', 699.00, 1, TIMESTAMP '2026-03-03 20:40:00')
ON CONFLICT (id) DO UPDATE SET
  order_id = EXCLUDED.order_id,
  product_id = EXCLUDED.product_id,
  product_name = EXCLUDED.product_name,
  product_description = EXCLUDED.product_description,
  image_url = EXCLUDED.image_url,
  unit_price = EXCLUDED.unit_price,
  quantity = EXCLUDED.quantity,
  created_at = EXCLUDED.created_at;

INSERT INTO chat_messages (id, user_id, role, content, created_at)
VALUES
  ('60000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'USER', '预算 2500 元以内，想买适合地铁通勤和视频会议的降噪耳机。', TIMESTAMP '2026-03-06 20:00:00'),
  ('60000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'ASSISTANT', '优先看索尼 WH-1000XM5 和华为 FreeBuds Pro 4，前者更适合沉浸式降噪，后者更适合通话与便携。', TIMESTAMP '2026-03-06 20:01:00'),
  ('60000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'USER', '再推荐一套适合桌面办公的键鼠组合，预算 1500。', TIMESTAMP '2026-03-07 12:20:00'),
  ('60000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'ASSISTANT', '可以组合罗技 MX Master 3S 和 Keychron K8 Pro，兼顾长时间办公、静音和多设备切换。', TIMESTAMP '2026-03-07 12:21:00')
ON CONFLICT (id) DO UPDATE SET
  user_id = EXCLUDED.user_id,
  role = EXCLUDED.role,
  content = EXCLUDED.content,
  created_at = EXCLUDED.created_at;

INSERT INTO product_views (id, user_id, product_id, source, reason, created_at)
VALUES
  ('70000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000003', 'chat-recommendation', '通勤降噪场景高匹配', TIMESTAMP '2026-03-08 08:30:00'),
  ('70000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000004', 'chat-recommendation', '适合会议与地铁通勤', TIMESTAMP '2026-03-08 09:00:00'),
  ('70000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000013', 'featured', '桌面办公升级配置', TIMESTAMP '2026-03-08 09:40:00'),
  ('70000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000016', 'featured', '高频办公用户常见搭配', TIMESTAMP '2026-03-08 10:10:00'),
  ('70000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000014', 'account-recommendation', '移动办公需求匹配', TIMESTAMP '2026-03-08 10:40:00'),
  ('70000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000015', 'account-recommendation', '差旅充电配件需求', TIMESTAMP '2026-03-08 11:00:00')
ON CONFLICT (id) DO UPDATE SET
  user_id = EXCLUDED.user_id,
  product_id = EXCLUDED.product_id,
  source = EXCLUDED.source,
  reason = EXCLUDED.reason,
  created_at = EXCLUDED.created_at;