import json
import random
from datetime import datetime, timezone
from pathlib import Path

OUTPUT_FILE = Path(__file__).resolve().parent / "products_500.json"

brands = [
    "华为", "小米", "联想", "荣耀", "OPPO", "vivo", "一加", "魅族", "戴尔", "罗技",
    "北面", "Columbia", "李宁", "安踏", "Fjallraven", "小米手环", "华为手表"
]

categories = [
    "数码办公", "个护母婴", "家居日用", "运动出行", "宠物用品",
    "男士服装", "女士服装", "厨房家电", "智能穿戴", "户外装备"
]

item_words = ["笔记本", "耳机", "背包", "夹克", "T恤", "湿巾", "奶粉", "运动鞋", "手表", "键盘"]
style_words = ["Pro", "Max", "Ultra", "智能", "高端", "经典", "轻薄", "专业", "旗舰"]

selling_points_pool = [
    "高性价比日常办公神器",
    "母婴护理安心之选",
    "户外出行专业装备",
    "时尚百搭舒适穿着",
    "智能便捷生活助手",
    "官方正品品质保证",
]

specs_pool = ["标准版", "Pro版", "黑色", "白色", "支持快充", "15英寸"]


def build_products(total: int = 500) -> list[dict]:
    products: list[dict] = []
    used_titles: set[str] = set()

    for i in range(total):
        brand = random.choice(brands)
        category = random.choice(categories)

        while True:
            model = random.randint(100, 9999)
            title = f"{brand}{random.choice(style_words)} {random.choice(item_words)} {model} 款"
            if title not in used_titles:
                used_titles.add(title)
                break

        product = {
            "product_id": f"prod-{10000 + i}",
            "title": title,
            "category": category,
            "price": round(random.uniform(89, 2999), 2),
            "specs": f"规格：{random.choice(specs_pool)}",
            "selling_points": random.choice(selling_points_pool),
            "policy": "官方正品 7天无理由退换 48小时内发货",
            "update_time": datetime.now(timezone.utc).isoformat(),
            "source": "mock-chinese-500",
        }
        products.append(product)

    return products


def main() -> None:
    products = build_products(500)
    with OUTPUT_FILE.open("w", encoding="utf-8") as f:
        json.dump(products, f, ensure_ascii=False, indent=2)

    print("500 条中文商品数据生成完成")
    print(f"文件：{OUTPUT_FILE}")
    print("可访问地址：http://localhost:8000/products_500.json")


if __name__ == "__main__":
    main()
