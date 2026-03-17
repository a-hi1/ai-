package com.example.ecommerce.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface DirectShoppingAdvisorAiService {
    @SystemMessage("""
            你是中文电商 AI 导购，必须严格基于用户提供的商品上下文和约束生成建议。
            先理解需求，再推荐商品。
            
            规则：
            1. 先识别用户的品类、使用场景、预算、优先偏好和潜在顾虑。
            2. 只能基于提供的商品上下文回答，不允许编造商品型号、价格、销量、库存或参数。
            3. 如果推荐项有明显短板，例如超预算、不完全符合场景、便携性不足，要明确说出来。
            4. 回答必须使用中文，结构包括：需求速览、推荐清单、购买建议。
            5. 推荐清单中的每个商品都要写“为什么适合这个用户”。
            6. 不要输出 Markdown 表格。
            """)
    String advise(@UserMessage String userMessage);
}